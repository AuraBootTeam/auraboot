package com.auraboot.framework.application.release;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Objects;

/** Internal shadow storage only. The caller supplies a trusted control-plane connection, never request credentials. */
public final class TenantApplicationShadowBindingStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    // Deliberately not a Spring service: no business DataSource fallback or public activation entry point.
    public TenantApplicationShadowBindingStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
        var manager = new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));
        manager.setValidateExistingTransaction(true);
        this.transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public record Binding(long tenantId, long applicationId, String releaseId, String releaseDigest,
                          int compatibilityEpoch, String status, long version) {}
    public record AuditContext(String actor, String operationId) {
        public AuditContext {
            require(actor != null && actor.matches("(user|ci|system|test):[A-Za-z0-9._:-]{1,200}"), "Authenticated binding actor required");
            require(operationId != null && operationId.matches("[0-9A-HJKMNP-TV-Z]{26}"), "Exact binding operation ID required");
        }
    }
    public static final class BindingConflictException extends RuntimeException {
        public BindingConflictException() { super("Shadow binding identity, version, or state conflict"); }
    }

    public Binding createShadow(long tenantId, long applicationId, String releaseId, String releaseDigest, AuditContext audit) {
        validate(tenantId, applicationId, releaseId, releaseDigest);
        return audited(audit, () -> {
            requireRelease(applicationId, releaseId, releaseDigest);
            jdbc.update("""
                    INSERT INTO ab_tenant_application_binding(tenant_id,application_id,current_release_id)
                    VALUES (?,?,?) ON CONFLICT (tenant_id,application_id) DO NOTHING
                    """, tenantId, applicationId, releaseId);
            var current = read(tenantId, applicationId);
            if (!current.releaseId().equals(releaseId) || !current.releaseDigest().equals(releaseDigest)
                    || !current.status().equals("shadow") || current.version() != 1) throw new BindingConflictException();
            return current;
        });
    }

    public Binding compareAndSetShadow(Binding expected, String targetReleaseId, String targetDigest, AuditContext audit) {
        Objects.requireNonNull(expected, "Expected binding required");
        validate(expected.tenantId(), expected.applicationId(), targetReleaseId, targetDigest);
        validate(expected.tenantId(), expected.applicationId(), expected.releaseId(), expected.releaseDigest());
        require(expected.version() > 0 && expected.version() < Long.MAX_VALUE
                && "shadow".equals(expected.status()), "Explicit shadow binding version required");
        require(!expected.releaseId().equals(targetReleaseId), "Target release must change");
        return audited(audit, () -> {
            require(requireRelease(expected.applicationId(), expected.releaseId(), expected.releaseDigest())
                    == expected.compatibilityEpoch(), "Expected release epoch mismatch");
            requireRelease(expected.applicationId(), targetReleaseId, targetDigest);
            int changed = jdbc.update("""
                    UPDATE ab_tenant_application_binding SET current_release_id=?,binding_version=binding_version+1
                    WHERE tenant_id=? AND application_id=? AND current_release_id=? AND binding_version=? AND status='shadow'
                    """, targetReleaseId, expected.tenantId(), expected.applicationId(), expected.releaseId(), expected.version());
            if (changed != 1) throw new BindingConflictException();
            return read(expected.tenantId(), expected.applicationId());
        });
    }

    private Binding audited(AuditContext audit, java.util.function.Supplier<Binding> operation) {
        Objects.requireNonNull(audit, "Binding audit context required");
        return transactions.execute(transaction -> {
            String previousActor = jdbc.queryForObject("SELECT current_setting('aura.binding.actor',true)", String.class);
            String previousOperation = jdbc.queryForObject("SELECT current_setting('aura.binding.operation',true)", String.class);
            jdbc.queryForObject("SELECT set_config('aura.binding.actor',?,true)", String.class, audit.actor());
            jdbc.queryForObject("SELECT set_config('aura.binding.operation',?,true)", String.class, audit.operationId());
            Binding result = operation.get();
            // Restore caller context on success. Exceptions roll back this transaction, including local settings.
            jdbc.queryForObject("SELECT set_config('aura.binding.actor',?,true)", String.class, previousActor == null ? "" : previousActor);
            jdbc.queryForObject("SELECT set_config('aura.binding.operation',?,true)", String.class, previousOperation == null ? "" : previousOperation);
            return result;
        });
    }

    private int requireRelease(long applicationId, String releaseId, String digest) {
        var matches = jdbc.query("""
                SELECT compatibility_epoch FROM ab_application_release WHERE application_id=? AND release_id=? AND digest=?
                """, (row, index) -> row.getInt(1), applicationId, releaseId, digest);
        require(matches.size() == 1, "Exact registered application release required");
        return matches.getFirst();
    }

    private Binding read(long tenantId, long applicationId) {
        return jdbc.queryForObject("""
                SELECT b.tenant_id,b.application_id,b.current_release_id,r.digest,r.compatibility_epoch,b.status,b.binding_version
                FROM ab_tenant_application_binding b JOIN ab_application_release r
                  ON r.application_id=b.application_id AND r.release_id=b.current_release_id
                WHERE b.tenant_id=? AND b.application_id=? FOR UPDATE OF b
                """, (row, index) -> new Binding(row.getLong(1), row.getLong(2), row.getString(3), row.getString(4),
                row.getInt(5), row.getString(6), row.getLong(7)), tenantId, applicationId);
    }

    private static void validate(long tenantId, long applicationId, String releaseId, String digest) {
        require(tenantId > 0 && applicationId > 0, "Positive tenant and application IDs required");
        require(releaseId != null && releaseId.matches("[0-9A-HJKMNP-TV-Z]{26}"), "Exact release ID required");
        require(digest != null && digest.matches("sha256:[0-9a-f]{64}"), "Exact release digest required");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
