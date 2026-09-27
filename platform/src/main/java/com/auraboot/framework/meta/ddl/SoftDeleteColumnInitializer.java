package com.auraboot.framework.meta.ddl;

import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.plugin.event.PluginImportCompletedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Validates soft-delete schema at startup; explicit imports publish dynamic columns. */
@Component
public class SoftDeleteColumnInitializer {
    private static final String SOFT_DELETE_TABLES_SQL = """
            SELECT DISTINCT table_name FROM public.ab_meta_model
            WHERE is_current = TRUE AND deleted_flag = FALSE
              AND source_type = 'physical'
              AND lower(extension ->> 'softDelete') = 'true'
              AND table_name IS NOT NULL
            ORDER BY table_name
            """;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate publicationTransaction;

    public SoftDeleteColumnInitializer(JdbcTemplate jdbcTemplate,
                                       PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.publicationTransaction = new TransactionTemplate(transactionManager);
        this.publicationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener
    public void onPluginImportCompleted(PluginImportCompletedEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // afterCommit propagates publication failures to the importing transaction caller.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishColumns();
                }
            });
        } else {
            publishColumns();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        inspectColumns(false);
    }

    private void publishColumns() {
        publicationTransaction.executeWithoutResult(status -> inspectColumns(true));
    }

    private void inspectColumns(boolean publish) {
        for (String table : jdbcTemplate.queryForList(SOFT_DELETE_TABLES_SQL, String.class)) {
            SqlSafetyUtils.validateIdentifier(table, "table name");
            String relation = "public." + table;
            if (jdbcTemplate.queryForObject("SELECT to_regclass(?)", String.class, relation) == null) {
                throw new IllegalStateException("Soft-delete model table is missing: " + relation);
            }
            Boolean exists = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_attribute
                    WHERE attrelid = to_regclass(?) AND attname = 'deleted_flag' AND NOT attisdropped)
                    """, Boolean.class, relation);
            if (!Boolean.TRUE.equals(exists)) {
                if (!publish || !table.startsWith("mt_")) {
                    throw new IllegalStateException("Missing deleted_flag on " + relation
                            + "; explicitly publish dynamic models or migrate static schema before startup");
                }
                jdbcTemplate.execute("ALTER TABLE public." + table
                        + " ADD COLUMN deleted_flag BOOLEAN NOT NULL DEFAULT FALSE");
            }
            Boolean valid = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_attribute
                    WHERE attrelid = to_regclass(?) AND attname = 'deleted_flag' AND NOT attisdropped
                      AND atttypid = 'boolean'::regtype AND attnotnull)
                    """, Boolean.class, relation);
            if (!Boolean.TRUE.equals(valid)) {
                throw new IllegalStateException("Invalid deleted_flag on " + relation
                        + "; BOOLEAN NOT NULL is required; explicit schema transition required");
            }
        }
    }
}
