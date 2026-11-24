package com.auraboot.framework.saas.bootstrap;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.*;

import static com.auraboot.framework.saas.executor.SystemTenantContextExecutor.SYSTEM_TENANT_ID;
import static com.auraboot.framework.saas.executor.SystemTenantContextExecutor.executeAsSystem;

/** Distribution-owned console seeds. Community ships no legacy console directory. */
@Service
@Slf4j
public class PlatformConsoleMenuBootstrap {
    record Definition(String code, String parent, String name, String path, int order, String icon) { }
    static final List<Definition> DEFINITIONS = List.of(
            new Definition("platform_overview", null, "Overview", "/platform/overview", 10, "ChartBarIcon"),
            new Definition("platform_tenants", null, "Tenants", "/platform/tenants", 20, "BuildingOfficeIcon"),
            new Definition("platform_commerce", null, "Commerce", null, 30, null),
            new Definition("platform_licenses", "platform_commerce", "Licenses", "/platform/licenses", 32, "KeyIcon"),
            new Definition("platform_infra", null, "Platform", null, 40, null),
            new Definition("platform_cloud_config", "platform_infra", "Cloud Config", "/platform/cloud-config", 41, "CloudIcon"),
            new Definition("platform_templates", "platform_infra", "Templates", "/platform/templates", 42, "DocumentDuplicateIcon"),
            new Definition("platform_plugins", "platform_infra", "Plugins", "/platform/plugins", 43, "PuzzlePieceIcon"),
            new Definition("platform_system", null, "System", null, 50, null),
            new Definition("platform_audit_logs", "platform_system", "Audit Log", "/platform/audit-logs", 51, "ClipboardDocumentListIcon"),
            new Definition("platform_settings", "platform_system", "Settings", "/platform/system-preferences", 52, "Cog6ToothIcon"));
    private static final Definition RETIRED_MARKETPLACE = new Definition(
            "platform_marketplace", "platform_commerce", "Marketplace", "/platform/marketplace", 31, "ShoppingBagIcon");
    private final JdbcTemplate jdbc;
    private final MenuMapper menus;
    private final Set<String> supported;

    @Autowired
    public PlatformConsoleMenuBootstrap(JdbcTemplate jdbc, MenuMapper menus, ObjectMapper json) throws IOException {
        this(jdbc, menus, readSupported(json, new PathMatchingResourcePatternResolver().getResources(
                "classpath*:META-INF/auraboot/platform-console-menus.json")));
    }

    PlatformConsoleMenuBootstrap(JdbcTemplate jdbc, MenuMapper menus, Set<String> supported) {
        this.jdbc = jdbc;
        this.menus = menus;
        this.supported = Set.copyOf(supported);
    }

    static Set<String> readSupported(ObjectMapper json, Resource[] resources) throws IOException {
        Set<String> result = new HashSet<>();
        Set<String> known = new HashSet<>();
        DEFINITIONS.forEach(d -> known.add(d.code()));
        for (Resource resource : resources) {
            try (var input = resource.getInputStream()) {
                var manifest = json.readTree(input);
                if (manifest.path("schemaVersion").asInt() != 1 || !manifest.path("menuCodes").isArray())
                    throw new IllegalStateException("Invalid platform console menu declaration");
                for (var code : manifest.path("menuCodes")) {
                    if (!code.isTextual() || !known.contains(code.asText()) || !result.add(code.asText()))
                        throw new IllegalStateException("Unknown or duplicate platform console menu code");
                }
            }
        }
        for (Definition definition : DEFINITIONS)
            if (result.contains(definition.code()) && definition.parent() != null && !result.contains(definition.parent()))
                throw new IllegalStateException("Platform console menu requires its parent declaration");
        return Set.copyOf(result);
    }

    @Transactional
    public void reconcile(Long tenantId, Long actorId) {
        if (!SYSTEM_TENANT_ID.equals(tenantId))
            throw new IllegalArgumentException("Platform console menus belong to the system tenant");
        executeAsSystem(() -> {
            reconcileSystemMenus(tenantId, actorId);
            return null;
        });
    }

    private void reconcileSystemMenus(Long tenantId, Long actorId) {
        // Delete exact, unowned bootstrap leaves first. Customized paths and plugin-owned menus survive.
        for (Definition definition : DEFINITIONS)
            if (definition.path() != null && !supported.contains(definition.code())) retire(tenantId, definition);
        retire(tenantId, RETIRED_MARKETPLACE);
        for (Definition definition : DEFINITIONS)
            if (definition.path() == null && !supported.contains(definition.code())) retire(tenantId, definition);

        Map<String, Menu> selected = new HashMap<>();
        for (Definition definition : DEFINITIONS) {
            if (!supported.contains(definition.code())) continue;
            Menu menu = menus.selectOne(new QueryWrapper<Menu>().eq("tenant_id", tenantId).eq("code", definition.code()));
            if (menu == null) {
                menu = new Menu();
                menu.setTenantId(tenantId); menu.setPid(UniqueIdGenerator.generate());
                menu.setParentId(definition.parent() == null ? null : selected.get(definition.parent()).getId());
                menu.setCode(definition.code()); menu.setName(definition.name()); menu.setPath(definition.path());
                menu.setType(definition.path() == null ? 0 : 1); menu.setOrderNo(definition.order());
                menu.setIcon(definition.icon()); menu.setVisible(true);
                menu.setCreatedBy(actorId); menu.setUpdatedBy(actorId); menus.insert(menu);
            }
            selected.put(definition.code(), menu);
        }
    }

    private void retire(Long tenantId, Definition definition) {
        var candidates = jdbc.queryForList("""
                SELECT m.id,m.pid FROM ab_menu m
                WHERE m.tenant_id=? AND m.plugin_pid IS NULL AND m.code=?
                  AND m.page_pid IS NULL AND m.page_key IS NULL AND m.component IS NULL
                  AND m.extension->'authoringManaged' IS DISTINCT FROM 'true'::jsonb
                  AND m.path IS NOT DISTINCT FROM CAST(? AS varchar)
                  AND NOT EXISTS (SELECT 1 FROM ab_menu child WHERE child.parent_id=m.id)
                  AND (m.path IS NULL OR NOT EXISTS (
                    SELECT 1 FROM ab_navigation_customization custom
                    WHERE custom.tenant_id=m.tenant_id AND custom.menu_pid=m.pid
                      AND (custom.definition->>'kind' IS DISTINCT FROM 'internal'
                        OR custom.definition->>'target' IS DISTINCT FROM m.path)))
                FOR UPDATE OF m
                """, tenantId, definition.code(), definition.path());
        for (var candidate : candidates) {
            String pid = (String) candidate.get("pid");
            jdbc.update("DELETE FROM ab_role_navigation_menu WHERE tenant_id=? AND menu_pid=?", tenantId, pid);
            jdbc.update("DELETE FROM ab_navigation_customization WHERE tenant_id=? AND menu_pid=?", tenantId, pid);
            // Physical deletion: no hidden/tombstone row that can be restored by a later menu import.
            jdbc.update("DELETE FROM ab_menu WHERE tenant_id=? AND id=? AND plugin_pid IS NULL", tenantId, candidate.get("id"));
            log.info("Removed unsupported bootstrap console menu {} from system tenant {}", definition.code(), tenantId);
        }
    }
}
