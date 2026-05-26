package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.BackgroundTenantAccessor;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.TenantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Default {@link BackgroundTenantAccessor} implementation — wraps the
 * host's {@link TenantService} so plugins can iterate active tenants
 * without depending on the host-internal class.
 *
 * @since 2.5.0
 */
@Slf4j
@Service
public class BackgroundTenantAccessorImpl implements BackgroundTenantAccessor {

    private final TenantService tenantService;

    public BackgroundTenantAccessorImpl(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @Override
    public List<Long> listActiveTenantIds() {
        try {
            return tenantService.getActiveTenants().stream()
                    .map(Tenant::getId)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("[tenant-accessor] listActiveTenantIds failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
