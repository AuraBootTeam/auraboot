package com.auraboot.framework.menu.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Repairs menu permission drift against each plugin import's declared snapshot.
 *
 * <p>{@code ab_plugin_resource.import_snapshot} captures the menus.json definition at
 * import time. The {@code permission_code} column is security-relevant: a row whose
 * binding is lost (manual edit, partial migration) renders an admin-area menu to every
 * member, because the navigation filter is allow-by-empty and directories with any
 * visible child survive pruning. The seed sync only inserts — a drifted row never
 * heals by itself, so this runner reconciles it at startup.
 *
 * <p>Repair direction is deliberately one-sided: only rows whose snapshot declares a
 * {@code permissionCode} but whose live column differs are restored. Rows the seed
 * declares without a binding are left alone, and {@code user_modified} resources are
 * skipped entirely — a deliberate user rebinding must not be silently reverted.
 */
@Slf4j
@Component
@Order(6)
@RequiredArgsConstructor
@Profile("!integration-test")
public class MenuPermissionDriftRepairRunner implements ApplicationRunner {

    private static final String DRIFT_QUERY =
            "SELECT pr.tenant_id, pr.resource_code, pr.user_modified, pr.import_snapshot::text, "
                    + "m.id AS menu_id, m.permission_code AS current_code, m.plugin_pid AS menu_plugin_pid, "
                    + "pr.plugin_pid AS resource_plugin_pid "
                    + "FROM ab_plugin_resource pr "
                    + "JOIN ab_menu m ON m.code = pr.resource_code AND m.tenant_id = pr.tenant_id "
                    + "WHERE pr.resource_type = 'menu' AND pr.import_snapshot IS NOT NULL "
                    + "AND m.deleted_flag = FALSE";

    private static final String REPAIR_SQL =
            "UPDATE ab_menu SET permission_code = ? WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) {
        List<long[]> repairedMenuIds = new java.util.ArrayList<>();
        int scanned = 0;
        int skippedUserModified = 0;
        int mismatchOther = 0;

        List<DriftRow> rows = jdbcTemplate.query(DRIFT_QUERY, (rs, i) -> new DriftRow(
                rs.getLong("tenant_id"),
                rs.getString("resource_code"),
                rs.getBoolean("user_modified"),
                rs.getString("import_snapshot"),
                rs.getLong("menu_id"),
                rs.getString("current_code"),
                rs.getObject("menu_plugin_pid"),
                rs.getObject("resource_plugin_pid")));

        for (DriftRow row : rows) {
            scanned++;
            if (row.userModified()) {
                skippedUserModified++;
                continue;
            }
            // The row must still belong to the plugin that declared the snapshot;
            // a re-parented or re-owned row is someone else's contract.
            if (!java.util.Objects.equals(row.menuPluginPid(), row.resourcePluginPid())) {
                mismatchOther++;
                continue;
            }
            String declared = readDeclaredPermissionCode(row.importSnapshot());
            if (declared == null || declared.isBlank()) {
                // Declared-unbound rows stay as they are: removing an existing binding
                // would weaken the menu's protection, never strengthen it.
                continue;
            }
            String current = row.currentCode();
            if (declared.equals(current)) continue;

            jdbcTemplate.update(REPAIR_SQL, declared, row.menuId());
            repairedMenuIds.add(new long[]{row.menuId(), row.tenantId()});
            log.warn("MenuPermissionDriftRepair: menu '{}' (tenant {}) permission_code restored to "
                    + "'{}' (was '{}')", row.resourceCode(), row.tenantId(), declared,
                    current == null ? "NULL" : current);
        }

        if (!repairedMenuIds.isEmpty()) {
            log.warn("MenuPermissionDriftRepair: repaired {} of {} scanned menu permission bindings "
                    + "({} user-modified skipped, {} ownership mismatches skipped)",
                    repairedMenuIds.size(), scanned, skippedUserModified, mismatchOther);
        } else {
            log.info("MenuPermissionDriftRepair: OK ({} plugin menus scanned, no drift)", scanned);
        }
    }

    private String readDeclaredPermissionCode(String snapshotJson) {
        try {
            JsonNode root = objectMapper.readTree(snapshotJson);
            JsonNode node = root.path("permissionCode");
            return node.isMissingNode() || node.isNull() ? null : node.asText();
        } catch (Exception e) {
            log.debug("MenuPermissionDriftRepair: unparseable import snapshot ignored: {}",
                    e.getMessage());
            return null;
        }
    }

    private record DriftRow(
            long tenantId,
            String resourceCode,
            boolean userModified,
            String importSnapshot,
            long menuId,
            String currentCode,
            Object menuPluginPid,
            Object resourcePluginPid) {
    }
}
