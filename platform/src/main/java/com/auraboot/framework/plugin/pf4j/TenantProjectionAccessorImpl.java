package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import com.auraboot.framework.plugin.extension.TenantProjectionAccessor;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Host bridge for tenant-bound, read-only projection commands. */
public final class TenantProjectionAccessorImpl implements TenantProjectionAccessor {

    private final long tenantId;
    private final BackgroundDataAccessor delegate;

    public TenantProjectionAccessorImpl(long tenantId, BackgroundDataAccessor delegate) {
        this.tenantId = tenantId;
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Map<String, Object> getById(String modelCode, String recordId) {
        return delegate.getById(tenantId, modelCode, recordId);
    }

    @Override
    public List<Map<String, Object>> query(String modelCode, Map<String, Object> filters) {
        return delegate.query(tenantId, modelCode, filters);
    }

    @Override
    public List<Map<String, Object>> queryIn(String modelCode, String fieldName,
                                             Collection<?> values) {
        return delegate.queryIn(tenantId, modelCode, fieldName, values);
    }
}
