package com.auraboot.framework.application.release;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Disabled-by-default least-privilege writer for preparing migration shadow bindings. */
@Service
public final class TenantApplicationShadowBindingService implements AutoCloseable {
    public static final class ShadowBindingUnavailableException extends IllegalStateException {
        public ShadowBindingUnavailableException() {
            super("Shadow binding connection is not enabled");
        }
    }

    private final com.zaxxer.hikari.HikariDataSource pool;
    private final TenantApplicationShadowBindingStore store;

    public TenantApplicationShadowBindingService(Environment environment) {
        if (!environment.getProperty("aura.binding.shadow.enabled", Boolean.class, false)) {
            pool = null; store = null; return;
        }
        pool = ShadowBindingConnection.open(environment);
        store = new TenantApplicationShadowBindingStore(new JdbcTemplate(pool));
    }

    public TenantApplicationShadowBindingStore.Binding createShadow(long tenantId, long applicationId, String releaseId, String digest,
            TenantApplicationShadowBindingStore.AuditContext audit) {
        requireEnabled(); return store.createShadow(tenantId, applicationId, releaseId, digest, audit);
    }
    public TenantApplicationShadowBindingStore.Binding createPublishedStableShadow(
            long tenantId, String applicationCode, String releaseId, String digest,
            TenantApplicationShadowBindingStore.AuditContext audit) {
        requireEnabled();
        return store.createPublishedStableShadow(tenantId, applicationCode, releaseId, digest, audit);
    }
    public TenantApplicationShadowBindingStore.Binding compareAndSetShadow(
            TenantApplicationShadowBindingStore.Binding expected, String releaseId, String digest,
            TenantApplicationShadowBindingStore.AuditContext audit) {
        requireEnabled(); return store.compareAndSetShadow(expected, releaseId, digest, audit);
    }
    private void requireEnabled() {
        if (store == null) throw new ShadowBindingUnavailableException();
    }
    @Override @jakarta.annotation.PreDestroy public void close() { if (pool != null) pool.close(); }
}
