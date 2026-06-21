package com.auraboot.framework.behavior.sitekey;

import com.auraboot.framework.meta.dto.IndexType;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.plugin.event.PluginImportCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Converges the global {@code UNIQUE(site_key)} index on the dynamic {@code mt_behavior_site_key}
 * table (anonymous-telemetry SP2, Option A).
 *
 * <p>Why this exists: config-level {@code unique}/{@code searchable} is inert on {@code mt_}
 * dynamic tables in this platform version, and Flyway cannot reach a table the plugin import
 * creates at runtime. So this reuses the platform's own idempotent
 * {@link SchemaManagementService#createFieldIndex} — which produces a <b>column-level, global</b>
 * unique index (not the tenant-prefixed shape {@code MultiTenantIndexManager} would emit) with a
 * built-in {@code indexExists} short-circuit. The index is what makes the unauthenticated
 * {@code resolveTenant} hot path an index scan and enforces global key uniqueness as
 * defense-in-depth behind the handler's {@code existsAnyTenant} pre-check.
 *
 * <p>Dual trigger, both calling the same idempotent path:
 * <ul>
 *   <li><b>On {@code behavior} plugin import</b> — the table has just been (re)created, so converge
 *       the index immediately. This is the primary, lifecycle-tied trigger.</li>
 *   <li><b>On app-ready</b> — a one-time backstop for an already-imported deployment whose table
 *       predates this code and that ships without a re-import. Guarded by table existence so a
 *       truly fresh DB (plugin not yet imported) is skipped and left to the import trigger.</li>
 * </ul>
 *
 * <p>See {@code docs/backlog/2026-06-21-mt-dynamic-table-index-creation-analysis.md}.
 */
@Slf4j
@Component
public class SiteKeyIndexInitializer {

    private static final String PLUGIN = "behavior";
    private static final String MODEL = "behavior_site_key";
    private static final String FIELD = "site_key";
    private static final String TABLE = "mt_behavior_site_key";

    private final SchemaManagementService schemaManagementService;
    private final JdbcTemplate jdbcTemplate;

    public SiteKeyIndexInitializer(SchemaManagementService schemaManagementService,
                                   JdbcTemplate jdbcTemplate) {
        this.schemaManagementService = schemaManagementService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener
    public void onPluginImportCompleted(PluginImportCompletedEvent event) {
        if (PLUGIN.equals(event.getPluginCode())) {
            ensureIndex();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (tableExists()) {
            ensureIndex();
        }
    }

    private void ensureIndex() {
        try {
            schemaManagementService.createFieldIndex(MODEL, FIELD, IndexType.UNIQUE);
        } catch (RuntimeException e) {
            // Index convergence must not break startup/import. createFieldIndex is idempotent
            // (indexExists short-circuit), so this only surfaces a genuine DDL failure for ops —
            // it does not retry or self-heal.
            log.warn("site_key unique index convergence failed: {}", e.getMessage());
        }
    }

    private boolean tableExists() {
        String reg = jdbcTemplate.queryForObject("SELECT to_regclass('" + TABLE + "')", String.class);
        return reg != null;
    }
}
