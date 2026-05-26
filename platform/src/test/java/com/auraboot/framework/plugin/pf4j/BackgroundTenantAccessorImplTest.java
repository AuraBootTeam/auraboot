package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackgroundTenantAccessorImplTest {

    private TenantService tenantService;
    private BackgroundTenantAccessorImpl accessor;

    @BeforeEach
    void setUp() {
        tenantService = mock(TenantService.class);
        accessor = new BackgroundTenantAccessorImpl(tenantService);
    }

    private static Tenant tenant(long id) {
        Tenant t = new Tenant();
        t.setId(id);
        return t;
    }

    @Test
    void listActiveTenantIds_returnsIdsFromService() {
        when(tenantService.getActiveTenants())
                .thenReturn(List.of(tenant(1L), tenant(42L), tenant(100L)));
        assertThat(accessor.listActiveTenantIds()).containsExactly(1L, 42L, 100L);
    }

    @Test
    void listActiveTenantIds_filtersOutNullIds() {
        Tenant nullId = new Tenant();
        // id intentionally left null
        when(tenantService.getActiveTenants()).thenReturn(List.of(tenant(1L), nullId, tenant(2L)));
        assertThat(accessor.listActiveTenantIds()).containsExactly(1L, 2L);
    }

    @Test
    void listActiveTenantIds_emptyWhenNoActiveTenants() {
        when(tenantService.getActiveTenants()).thenReturn(List.of());
        assertThat(accessor.listActiveTenantIds()).isEmpty();
    }

    @Test
    void listActiveTenantIds_returnsEmptyWhenServiceThrows_doesNotPropagate() {
        when(tenantService.getActiveTenants()).thenThrow(new RuntimeException("DB down"));
        // Resilient: a transient host failure should not kill the caller's
        // scheduler loop. Empty list = same effect as "no tenants this tick".
        assertThat(accessor.listActiveTenantIds()).isEmpty();
    }
}
