package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NamedQueryFieldProtectionAuthorizationTest {
    private final DataPermissionEngine policies = mock(DataPermissionEngine.class);
    private final FieldMaskService masks = mock(FieldMaskService.class);
    private final MetaModelService models = mock(MetaModelService.class);
    private final NamedQuerySourceModels sources = mock(NamedQuerySourceModels.class);
    private final PermissionEvaluator permissions = mock(PermissionEvaluator.class);
    private final NamedQueryFieldProtection protection = new NamedQueryFieldProtection(policies, masks, models, sources, mock(DataDomainService.class), permissions);
    private final NamedQuery query = new NamedQuery();

    @BeforeEach void setup() {
        MetaContext.setContext(10L, 20L, "user", "User");
        MetaContext.setMemberId(30L);
        query.setFromSql("orders JOIN customers ON orders.customer = customers.pid");
        when(sources.resolve(10L, query.getFromSql(), List.of())).thenReturn(Map.of("orders", "orders", "customers", "customers"));
    }
    @AfterEach void cleanup() { MetaContext.clear(); }

    @Test void deniedJoinedSourceStopsBeforeMaskRulesAreLoaded() {
        query.setResourceCode("orders");
        when(permissions.canAction(30L, "orders", "read")).thenReturn(true);
        AccessDeniedException denied = assertThrows(AccessDeniedException.class, () -> protection.prepare(query, List.of(), "list"));
        assertEquals("Access denied for named query source: customers", denied.getMessage());
        verifyNoInteractions(policies, masks, models);
    }
    @Test void everyPhysicalSourceUsesCurrentMemberIdentity() {
        when(permissions.canAction(30L, "orders", "read")).thenReturn(true);
        when(permissions.canAction(30L, "customers", "read")).thenReturn(true);
        var plan = protection.prepare(query, List.of(), "list");
        assertEquals(Map.of("orders", "(tenant_id = 10)", "customers", "(tenant_id = 10)"), plan.sourceScopes());
        verify(permissions).canAction(30L, "orders", "read");
        verify(permissions).canAction(30L, "customers", "read");
        verify(permissions, never()).canAction(eq(20L), anyString(), anyString());
    }
    @Test void missingMemberUsesCurrentUserWithoutSkippingAuthorization() {
        MetaContext.setMemberId(null);
        when(permissions.canAction(20L, "orders", "read")).thenReturn(true);
        when(permissions.canAction(20L, "customers", "read")).thenReturn(true);
        assertNotNull(protection.prepare(query, List.of()));
        verify(permissions).canAction(20L, "orders", "read");
        verify(permissions).canAction(20L, "customers", "read");
    }
}
