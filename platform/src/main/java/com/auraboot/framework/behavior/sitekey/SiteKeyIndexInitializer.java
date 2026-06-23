package com.auraboot.framework.behavior.sitekey;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.IndexType;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.plugin.event.PluginImportCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
 *   <li><b>On {@code behavior} plugin import (after commit)</b> — the table has just been created
 *       by the import. This is the primary, lifecycle-tied trigger. It MUST run
 *       {@link TransactionPhase#AFTER_COMMIT}: the import publishes the event from inside its own
 *       (still-open) transaction, and {@code createFieldIndex} checks table existence on a separate
 *       connection that cannot see the uncommitted {@code CREATE TABLE} — a plain {@code @EventListener}
 *       fails with "Table does not exist" and the index never converges until the next restart
 *       (caught by SP4's real import golden; SP2's in-process IT could not exercise this path).
 *       {@code fallbackExecution = true} keeps it working if a future import path runs without a tx.</li>
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPluginImportCompleted(PluginImportCompletedEvent event) {
        if (PLUGIN.equals(event.getPluginCode())) {
            ensureIndex();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!tableExists()) {
            return;
        }
        // createFieldIndex → getModelDefinition logs the meta operation, which reads the current
        // tenant id and fails on a bare startup thread ("Tenant context is required but not found").
        // The index DDL itself is global (single column, no tenant prefix); borrow the model's
        // owning tenant only to satisfy the logging path, then restore the previous context.
        Long owningTenant = modelOwningTenant();
        if (owningTenant == null) {
            log.warn("site_key index backstop skipped: no owning tenant for model {} (leaving to import trigger)", MODEL);
            return;
        }
        // The startup thread has no MetaContext (reading it throws "not initialized"), so there is
        // no caller context to preserve — set the owning tenant, converge, then clear unconditionally.
        try {
            MetaContext.setCurrentTenantId(owningTenant);
            ensureIndex();
        } finally {
            MetaContext.clear();
        }
    }

    private Long modelOwningTenant() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT tenant_id FROM ab_meta_model WHERE code = ? ORDER BY id LIMIT 1",
                    Long.class, MODEL);
        } catch (RuntimeException e) {
            return null;
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
