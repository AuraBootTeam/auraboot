package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.PluginRequestContext;
import com.auraboot.framework.plugin.extension.IdentityDirectoryAccessor;
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
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class PluginRequestContextFactory {

    private final TenantClock tenantClock;
    private final DynamicDataService dynamicDataService;
    private final IdentityDirectoryAccessor identities;
    private final com.auraboot.framework.permission.service.UserPermissionService permissions;

    public PluginRequestContextFactory(TenantClock clock,DynamicDataService data,IdentityDirectoryAccessor identities) {
        this(clock,data,identities,null);
    }

    public PluginRequestContext current(boolean isPublic,com.auraboot.framework.plugin.extension.RestRoute route,java.util.Set<String> models) {
        if(models==null || models.isEmpty() || models.size()>16
                || models.stream().anyMatch(model->model==null || !model.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
                || isPublic || route==null || route.authPolicy()!=com.auraboot.framework.plugin.extension.AuthPolicy.AUTHENTICATED
                || !java.util.Set.of("GET","POST").contains(route.method()) || !route.readOnlyTx() || route.permissionCode()==null || route.permissionCode().isBlank()
                || permissions==null)throw new org.springframework.security.access.AccessDeniedException("Authenticated read-only route declaration is required");
        Long tenant=MetaContext.getCurrentTenantId(),user=MetaContext.getCurrentUserId();
        if(tenant==null || tenant<=0 || user==null || user<=0)throw new org.springframework.security.access.AccessDeniedException("Route projection identity is unavailable");
        var data=new RestProjectionDataAccessor(new DynamicDataAccessorImpl(dynamicDataService),
                new com.auraboot.framework.plugin.pf4j.TenantProjectionAccessorImpl(tenant,
                        new com.auraboot.framework.plugin.pf4j.BackgroundDataAccessorImpl(dynamicDataService)),models,
                ()->permissions.hasPermission(user,route.permissionCode()));
        return current(false,data);
    }

    public PluginRequestContext current(boolean isPublic) {
        return current(isPublic,new DynamicDataAccessorImpl(dynamicDataService));
    }

    private PluginRequestContext current(boolean isPublic,DataAccessor dataAccessor) {
        final Long tenantId = MetaContext.getCurrentTenantId();
        final Long userId = MetaContext.getCurrentUserId();
        final ZoneId zone = tenantId != null ? tenantClock.getZoneId(tenantId) : ZoneId.systemDefault();
        return new PluginRequestContext() {
            @Override public Long tenantId() { return tenantId; }
            @Override public Long userId() { return userId; }
            @Override public ZoneId zoneId() { return zone; }
            @Override public String generateId() { return UniqueIdGenerator.generate(); }
            @Override public boolean isPublic() { return isPublic; }
            @Override public DataAccessor dataAccessor() { return dataAccessor; }
            @Override public IdentityDirectoryAccessor.UserIdentity findActiveTenantUser(String userPid) {
                if(isPublic || tenantId==null || userId==null || userId<=0 || userPid==null || userPid.isBlank()) return null;
                return identities.findActiveTenantUser(tenantId,userPid);
            }
        };
    }
}
