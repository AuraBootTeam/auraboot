package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.plugin.extension.PluginRequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * Builds a {@link PluginRequestContext} for the current request by bridging host internals
 * (MetaContext / TenantClock / UniqueIdGenerator) into the neutral plugin-facing context.
 * This is the host-side seam that lets plugin handlers stay decoupled from those internals.
 */
@Component
@RequiredArgsConstructor
public class PluginRequestContextFactory {

    private final TenantClock tenantClock;

    public PluginRequestContext current(boolean isPublic) {
        final Long tenantId = MetaContext.getCurrentTenantId();
        final Long userId = MetaContext.getCurrentUserId();
        final ZoneId zone = tenantId != null ? tenantClock.getZoneId(tenantId) : ZoneId.systemDefault();
        return new PluginRequestContext() {
            @Override public Long tenantId() { return tenantId; }
            @Override public Long userId() { return userId; }
            @Override public ZoneId zoneId() { return zone; }
            @Override public String generateId() { return UniqueIdGenerator.generate(); }
            @Override public boolean isPublic() { return isPublic; }
        };
    }
}
