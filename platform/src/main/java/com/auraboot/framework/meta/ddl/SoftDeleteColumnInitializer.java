package com.auraboot.framework.meta.ddl;

import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.plugin.event.PluginImportCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Back-fills the physical {@code deleted_flag} column on dynamic {@code mt_} tables whose model
 * opts into soft delete via {@code extension.softDelete = true}.
 *
 * <p>Why this exists: {@code SchemaManagementServiceImpl.generateCreateTableDDL} now emits a
 * {@code deleted_flag} column for soft-delete models at CREATE time, but tables that were imported
 * before a model turned on soft delete (or before this code shipped) already exist, and Flyway
 * cannot reach a table the plugin import creates at runtime. So this reuses the same dual trigger
 * as {@code SiteKeyIndexInitializer}: idempotent {@code ALTER TABLE ... ADD COLUMN}, guarded by
 * {@code columnExists}, so an already-converged table is a no-op.
 *
 * <p>Unlike SiteKeyIndexInitializer this needs no MetaContext: the discovery query reads
 * {@code ab_meta_model} directly and the ALTER is a global DDL. {@code AFTER_COMMIT} is still
 * required on the import trigger — the import publishes the event from inside its still-open
 * transaction and a separate connection cannot see the uncommitted {@code CREATE TABLE}; a plain
 * {@code @EventListener} would fail with "table does not exist" until the next restart.
 */
@Slf4j
@Component
public class SoftDeleteColumnInitializer {

    /**
     * Soft delete is stored in the model's {@code extension} JSONB as either a JSON boolean
     * {@code true} or the string {@code "true"} (see {@code MetaModelServiceImpl.resolveSoftDelete}),
     * both of which {@code ->>} renders as the text {@code 'true'}.
     */
    private static final String SOFT_DELETE_TABLES_SQL =
            "SELECT DISTINCT table_name FROM ab_meta_model "
                    + "WHERE is_current = TRUE AND deleted_flag = FALSE "
                    + "AND lower(extension ->> 'softDelete') = 'true' "
                    + "AND table_name IS NOT NULL";

    private final TableMetadataService tableMetadataService;
    private final JdbcTemplate jdbcTemplate;

    public SoftDeleteColumnInitializer(TableMetadataService tableMetadataService,
                                       JdbcTemplate jdbcTemplate) {
        this.tableMetadataService = tableMetadataService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPluginImportCompleted(PluginImportCompletedEvent event) {
        // Any plugin import may have just created a soft-delete model's table; the whole set is
        // reconciled idempotently (columnExists short-circuits already-converged tables).
        ensureAllSoftDeleteColumns();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // Backstop for already-imported deployments whose tables predate this code.
        ensureAllSoftDeleteColumns();
    }

    private void ensureAllSoftDeleteColumns() {
        List<String> tables;
        try {
            tables = jdbcTemplate.queryForList(SOFT_DELETE_TABLES_SQL, String.class);
        } catch (RuntimeException e) {
            log.warn("soft-delete column convergence: could not list soft-delete models: {}", e.getMessage());
            return;
        }
        for (String table : tables) {
            ensureDeletedFlagColumn(table);
        }
    }

    private void ensureDeletedFlagColumn(String table) {
        try {
            if (table == null || !tableMetadataService.tableExists(table)) {
                return; // model maps to a table not yet created — leave it to the import trigger
            }
            if (tableMetadataService.columnExists(table, "deleted_flag")) {
                return; // already present (ab_* baseline tables, or a prior convergence)
            }
            // table_name comes from ab_meta_model, not user input, but validate defensively
            // since it is interpolated into DDL.
            SqlSafetyUtils.validateIdentifier(table, "table name");
            jdbcTemplate.execute("ALTER TABLE " + table
                    + " ADD COLUMN deleted_flag BOOLEAN NOT NULL DEFAULT FALSE");
            log.info("soft-delete: added deleted_flag column to {}", table);
        } catch (RuntimeException e) {
            // Convergence must not break startup/import; the ALTER is idempotent via columnExists,
            // so this only surfaces a genuine DDL failure for ops — it does not retry or self-heal.
            log.warn("soft-delete column convergence failed for {}: {}", table, e.getMessage());
        }
    }
}
