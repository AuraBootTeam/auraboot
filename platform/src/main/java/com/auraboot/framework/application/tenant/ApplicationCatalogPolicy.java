package com.auraboot.framework.application.tenant;

import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.permission.capability.CapabilityGroup;
import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import com.auraboot.framework.tenant.service.TenantPreferenceService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Product-local catalog projection. Never used to decide business or data authorization. */
@Service
public class ApplicationCatalogPolicy {
    public static final String KEY_PREFIX = "application.catalog.";
    private static final Set<String> KEYS = Set.of("version", "applicationCode", "menuCodes", "capabilityCodes", "pageKeys");
    private static final Scope UNSCOPED = new Scope(null, Set.of(), Set.of(), Set.of());
    private final TenantPreferenceService preferences;
    private final String applicationCode;

    public ApplicationCatalogPolicy(TenantPreferenceService preferences,
            @Value("${auraboot.application-catalog-code:}") String applicationCode) {
        this.preferences = preferences;
        this.applicationCode = applicationCode;
        if (!applicationCode.isEmpty()) key(applicationCode);
    }

    public static String key(String applicationCode) {
        if (applicationCode == null || !applicationCode.matches("[a-z][a-z0-9.-]{0,79}")) {
            throw new IllegalArgumentException("Invalid application catalog identity");
        }
        return KEY_PREFIX + applicationCode;
    }

    public Scope read(Long tenant) {
        if (tenant == null || tenant <= 0 || MetaContext.exists()
                && !tenant.equals(MetaContext.getCurrentTenantId())) {
            throw new AccessDeniedException("Owned catalog tenant required");
        }
        if (applicationCode.isEmpty()) return UNSCOPED;
        JsonNode value = preferences.getPreference(tenant, key(applicationCode));
        if (value == null) return UNSCOPED;
        Scope scope = parse(value);
        if (!applicationCode.equals(scope.applicationCode())) throw new IllegalStateException("Application catalog identity mismatch");
        return scope;
    }

    public static Scope parse(JsonNode value) {
        Set<String> keys = new HashSet<>();
        if (value != null) value.fieldNames().forEachRemaining(keys::add);
        if (value == null || !value.isObject() || !keys.equals(KEYS)
                || !value.path("version").isIntegralNumber() || !value.path("version").canConvertToInt()
                || value.path("version").intValue() != 1
                || !value.path("applicationCode").isTextual()
                || !value.path("applicationCode").asText().matches("[a-z][a-z0-9.-]{0,79}")) {
            throw new IllegalStateException("Invalid application catalog declaration");
        }
        return new Scope(value.path("applicationCode").asText(), codes(value, "menuCodes"),
                codes(value, "capabilityCodes"), codes(value, "pageKeys"));
    }

    private static Set<String> codes(JsonNode value, String key) {
        JsonNode array = value.path(key);
        if (!array.isArray() || array.size() > 5000) throw new IllegalStateException("Invalid application catalog " + key);
        var result = new TreeSet<String>();
        for (JsonNode item : array) {
            if (!item.isTextual() || !item.asText().matches("[A-Za-z0-9_.:-]{1,256}") || !result.add(item.asText())) {
                throw new IllegalStateException("Invalid or duplicate application catalog " + key);
            }
        }
        return Collections.unmodifiableSortedSet(result);
    }

    public record Scope(String applicationCode, Set<String> menuCodes, Set<String> capabilityCodes, Set<String> pageKeys) {
        public boolean active() { return applicationCode != null; }
        public boolean capability(String code) { return !active() || capabilityCodes.contains(code); }
        public boolean page(String key) { return !active() || pageKeys.contains(key); }

        public List<CapabilityDefinitionDTO> declarations(List<CapabilityDefinitionDTO> all) {
            return !active() ? all : all.stream().filter(item -> capability(item.getCode())).toList();
        }

        public List<CapabilityGroup> groups(List<CapabilityGroup> all) {
            if (!active()) return all;
            return all.stream().map(group -> new CapabilityGroup(group.getGroup(), group.getCapabilities().stream()
                            .filter(item -> capability(item.getCode())).toList()))
                    .filter(group -> !group.getCapabilities().isEmpty()).toList();
        }

        /** Project default roots; preserve explicit tenant navigation and its original ancestors. */
        public List<Menu> menus(List<Menu> all) {
            if (!active()) return all;
            var byId = new HashMap<Long, Menu>();
            all.stream().filter(menu -> menu.getId() != null).forEach(menu -> byId.put(menu.getId(), menu));
            var kept = new HashSet<Menu>();
            for (Menu menu : all) {
                var extension = menu.getExtension();
                boolean customized = extension != null && (Boolean.TRUE.equals(extension.get("navigationCustom"))
                        || Boolean.TRUE.equals(extension.get("navigationCustomized")));
                if (!customized) {
                    if (menuCodes.contains(menu.getCode())) kept.add(menu);
                    continue;
                }
                Menu current = menu;
                var visited = new HashSet<Menu>();
                while (current != null && visited.add(current)) {
                    kept.add(current);
                    current = byId.get(current.getParentId());
                }
            }
            var ids = kept.stream().map(Menu::getId).collect(java.util.stream.Collectors.toSet());
            return all.stream().filter(kept::contains).map(menu -> {
                if (menu.getParentId() == null || ids.contains(menu.getParentId())) return menu;
                var projected = new Menu();
                org.springframework.beans.BeanUtils.copyProperties(menu, projected);
                projected.setParentId(null);
                return projected;
            }).toList();
        }
    }
}
