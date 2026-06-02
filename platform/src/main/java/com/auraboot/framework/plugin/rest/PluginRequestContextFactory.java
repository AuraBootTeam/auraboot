package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.PluginRequestContext;
import com.auraboot.framework.plugin.pf4j.DynamicDataAccessorImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * Builds a {@link PluginRequestContext} for the current request by bridging host internals
 * (MetaContext / TenantClock / UniqueIdGenerator / DynamicDataService) into the neutral
 * plugin-facing context. This is the host-side seam that lets plugin REST handlers stay
 * decoupled from those internals while still doing governed, tenant-scoped CRUD.
 */
@Component
@RequiredArgsConstructor
public class PluginRequestContextFactory {

    private final TenantClock tenantClock;
    private final DynamicDataService dynamicDataService;

    public PluginRequestContext current(boolean isPublic) {
        final Long tenantId = MetaContext.getCurrentTenantId();
        final Long userId = MetaContext.getCurrentUserId();
        final ZoneId zone = tenantId != null ? tenantClock.getZoneId(tenantId) : ZoneId.systemDefault();
        final DataAccessor dataAccessor = new DynamicDataAccessorImpl(dynamicDataService);
        return new PluginRequestContext() {
            @Override public Long tenantId() { return tenantId; }
            @Override public Long userId() { return userId; }
            @Override public ZoneId zoneId() { return zone; }
            @Override public String generateId() { return UniqueIdGenerator.generate(); }
            @Override public boolean isPublic() { return isPublic; }
            @Override public DataAccessor dataAccessor() { return dataAccessor; }
        };
    }
}
