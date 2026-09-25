package com.auraboot.framework.behavior.sitekey;

import com.auraboot.framework.meta.dto.IndexType;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.service.SchemaManagementService;
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

/**
 * Publishes the global site-key unique index after explicit behavior plugin import.
 * Application startup validates the existing index without modifying schema or tenant context.
 * Missing or incompatible indexes require an explicit model publication before deployment.
 */
@Component
public class SiteKeyIndexInitializer {

    private static final String PLUGIN = "behavior";
    private static final String MODEL = "behavior_site_key";
    private static final String FIELD = "site_key";
    private static final String TABLE = "mt_behavior_site_key";

    private final SchemaManagementService schemaManagementService;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate publicationTransaction;

    public SiteKeyIndexInitializer(SchemaManagementService schemaManagementService,
                                   JdbcTemplate jdbcTemplate,
                                   PlatformTransactionManager transactionManager) {
        this.schemaManagementService = schemaManagementService;
        this.jdbcTemplate = jdbcTemplate;
        this.publicationTransaction = new TransactionTemplate(transactionManager);
        this.publicationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @EventListener
    public void onPluginImportCompleted(PluginImportCompletedEvent event) {
        if (!PLUGIN.equals(event.getPluginCode())) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // afterCommit propagates failures to the importing TransactionTemplate; an
            // AFTER_COMMIT event listener runs in afterCompletion and only logs failures.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishIndex();
                }
            });
        } else {
            publishIndex();
        }
    }

    private void publishIndex() {
        // Committed import resources may still be bound to this thread. The DDL and its
        // postcondition need their own transaction, including a real commit or rollback.
        publicationTransaction.executeWithoutResult(status -> ensureIndex());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!tableExists()) {
            return;
        }
        validateIndex();
    }

    private void validateIndex() {
        Boolean valid = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_index i
                    JOIN pg_catalog.pg_attribute a
                      ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
                    WHERE i.indrelid = to_regclass('public.mt_behavior_site_key')
                      AND i.indisunique AND i.indisvalid AND i.indisready
                      AND i.indnkeyatts = 1 AND i.indpred IS NULL AND i.indexprs IS NULL
                      AND a.attname = 'site_key' AND NOT a.attisdropped
                )
                """, Boolean.class);
        if (!Boolean.TRUE.equals(valid)) {
            throw new IllegalStateException(
                    "behavior_site_key requires a valid global UNIQUE(site_key) index; "
                    + "publish the behavior model explicitly before starting the application");
        }
    }

    private void ensureIndex() {
        SchemaOperationResult result = schemaManagementService.createFieldIndex(MODEL, FIELD, IndexType.UNIQUE);
        if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
            throw new IllegalStateException("behavior_site_key index publication failed");
        }
        validateIndex();
    }

    private boolean tableExists() {
        String reg = jdbcTemplate.queryForObject("SELECT to_regclass('public." + TABLE + "')", String.class);
        return reg != null;
    }
}
