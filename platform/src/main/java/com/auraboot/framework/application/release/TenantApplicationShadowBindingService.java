package com.auraboot.framework.application.release;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Disabled-by-default control service; no activation or public tenant mutation API. */
@Service
public final class TenantApplicationShadowBindingService implements AutoCloseable {
    private final com.zaxxer.hikari.HikariDataSource pool;
    private final TenantApplicationShadowBindingStore store;

    public TenantApplicationShadowBindingService(Environment environment) {
        if (!environment.getProperty("aura.binding.shadow.enabled", Boolean.class, false)) {
            pool = null; store = null; return;
        }
        pool = ShadowBindingConnection.open(environment);
        store = new TenantApplicationShadowBindingStore(new JdbcTemplate(pool));
    }

    public TenantApplicationShadowBindingStore.Binding createShadow(long tenantId, long applicationId, String releaseId, String digest) {
        requireEnabled(); return store.createShadow(tenantId, applicationId, releaseId, digest);
    }
    public TenantApplicationShadowBindingStore.Binding compareAndSetShadow(
            TenantApplicationShadowBindingStore.Binding expected, String releaseId, String digest) {
        requireEnabled(); return store.compareAndSetShadow(expected, releaseId, digest);
    }
    private void requireEnabled() {
        if (store == null) throw new IllegalStateException("Shadow binding connection is not enabled");
    }
    @Override @jakarta.annotation.PreDestroy public void close() { if (pool != null) pool.close(); }
}
