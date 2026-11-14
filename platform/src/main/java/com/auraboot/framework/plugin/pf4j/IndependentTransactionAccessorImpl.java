package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.IndependentTransactionAccessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Function;

/** Platform implementation of the plugin checkpoint transaction bridge. */
public final class IndependentTransactionAccessorImpl implements IndependentTransactionAccessor {

    private final TransactionTemplate requiresNew;
    private final DataAccessor dataAccessor;
    private final javax.sql.DataSource lockSource;

    public IndependentTransactionAccessorImpl(PlatformTransactionManager transactionManager,
                                              DynamicDataService dynamicDataService) {
        Objects.requireNonNull(transactionManager, "transactionManager");
        Objects.requireNonNull(dynamicDataService, "dynamicDataService");
        this.lockSource = transactionManager instanceof org.springframework.jdbc.datasource.DataSourceTransactionManager jdbc
                ? jdbc.getDataSource() : null;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Unbounded legacy deliveries use ordinary complete-grant field checks inside each checkpoint.
        if (com.auraboot.framework.meta.service.impl.AsyncTaskExecutionContext.isActive())
            this.requiresNew.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.dataAccessor = new DynamicDataAccessorImpl(dynamicDataService);
    }

    @Override
    public <T> T requiresNew(Function<DataAccessor, T> work) {
        Objects.requireNonNull(work, "work");
        return requiresNew.execute(status -> work.apply(dataAccessor));
    }
    @Override
    public <T> T requiresNewSerialized(String modelCode, String recordPid, Function<DataAccessor, T> work) {
        Objects.requireNonNull(work, "work");
        Long tenant = com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId();
        if (tenant == null || tenant <= 0 || modelCode == null
                || !modelCode.matches("[A-Za-z][A-Za-z0-9_]{0,127}") || recordPid == null
                || recordPid.isBlank() || !recordPid.equals(recordPid.strip()) || recordPid.length() > 64)
            throw new IllegalArgumentException("An authenticated exact record is required");
        if (lockSource == null) throw new UnsupportedOperationException("A Core JDBC transaction manager is required");
        requireReadableRoot(modelCode, recordPid, tenant);
        // Hold only an advisory lock on this unbound connection. The ordinary independent
        // transaction starts afterwards, so even REPEATABLE_READ sees the preceding replacement.
        // No domain row or version is written merely to coordinate writers.
        try (java.sql.Connection lock = lockSource.getConnection()) {
            if (!lock.getAutoCommit() || org.springframework.jdbc.datasource.DataSourceUtils
                    .isConnectionTransactional(lock, lockSource))
                throw new IllegalStateException("A separate lock connection is required");
            lock.setAutoCommit(false);
            Throwable failure = null;
            try {
                try (var statement = lock.prepareStatement(
                        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                    statement.setQueryTimeout(30);
                    statement.setString(1, "plugin-checkpoint:" + tenant + ":" + modelCode + ":" + recordPid);
                    statement.execute();
                }
                return requiresNew(db -> {
                    requireReadableRoot(modelCode, recordPid, tenant);
                    return work.apply(db);
                });
            } catch (RuntimeException | Error error) {
                failure = error;
                throw error;
            } finally {
                try {
                    lock.rollback();
                } catch (java.sql.SQLException releaseFailure) {
                    // A connection whose lock transaction cannot be rolled back must not return
                    // to the pool with a live lock. Preserve the original callback failure.
                    try { lock.abort(Runnable::run); }
                    catch (java.sql.SQLException abortFailure) { releaseFailure.addSuppressed(abortFailure); }
                    if (failure != null) failure.addSuppressed(releaseFailure);
                    else throw releaseFailure;
                }
            }
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException("Cannot serialize the independent record replacement", error);
        }
    }

    private void requireReadableRoot(String modelCode, String recordPid, Long tenant) {
        var row = dataAccessor.getById(modelCode, recordPid);
        if (row == null || !recordPid.equals(row.get("pid")) || (row.get("tenant_id") != null
                && !tenant.toString().equals(String.valueOf(row.get("tenant_id")))))
            throw new org.springframework.security.access.AccessDeniedException("Serialized checkpoint root is unavailable");
    }

}
