package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.entity.NamedQueryPolicy;
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
        when(sources.resolvePlan(10L, query.getFromSql(), List.of())).thenReturn(new NamedQuerySourceModels.Sources(Map.of("orders", "orders", "customers", "customers"), Map.of()));
    }
    @AfterEach void cleanup() { MetaContext.clear(); }

    @Test void deniedJoinedSourceStopsBeforeMaskRulesAreLoaded() {
        query.setResourceCode("orders");
        when(permissions.canAction(30L, "orders", "read")).thenReturn(true);
        AccessDeniedException denied = assertThrows(AccessDeniedException.class, () -> protection.prepare(query, List.of(), "list"));
        assertEquals("Access denied for named query source: customers", denied.getMessage());
        verifyNoInteractions(policies, masks, models);
    }
    @Test void platformReferenceSourceSkipsModelCheckAndTakesNoRowScope() {
        // Platform reference sources (e.g. the ab_tenant_member directory joined by the
        // quote/BOM dashboard charts for display names) carry no model permission and no
        // row surface: they must skip the per-source model check entirely and take the
        // "true" scope instead of any tenant/row predicate (107 gate 2026-09-20: every
        // dashboard chart-data request denied for business roles without this).
        when(sources.resolvePlan(10L, query.getFromSql(), List.of())).thenReturn(new NamedQuerySourceModels.Sources(
                Map.of("orders", "orders", "\"public\".\"ab_tenant_member\"", NamedQuerySourceModels.PLATFORM_REFERENCE_MARKER),
                Map.of()));
        when(permissions.canAction(30L, "orders", "read")).thenReturn(true);
        var plan = protection.prepare(query, List.of(), "list");
        assertEquals("true", plan.sourceScopes().get("\"public\".\"ab_tenant_member\""));
        assertEquals("(tenant_id = 10)", plan.sourceScopes().get("orders"));
        verify(permissions, never()).canAction(anyLong(), eq(NamedQuerySourceModels.PLATFORM_REFERENCE_MARKER), anyString());
    }
    @Test void selfAnchoredPolicyReplacesMissingModelReadWithCreatedByAnchor() {
        // Self-contribution analytics (home trend/workload charts) must stay executable for
        // members without any product role: the policy declares the source is only consumed
        // through rows the current user created, so the missing model read downgrades to a
        // forced created_by anchor instead of a denial — narrower than any model grant.
        var policy = new NamedQueryPolicy();
        policy.setSelfAnchoredSources(true);
        query.setPolicy(policy);
        when(permissions.canAction(30L, "orders", "read")).thenReturn(false);
        var plan = protection.prepare(query, List.of(), "list");
        assertEquals("(tenant_id = 10 AND created_by = 20)", plan.sourceScopes().get("orders"));
    }
    @Test void withoutSelfAnchoredPolicyMissingModelReadStillDenied() {
        when(permissions.canAction(30L, "customers", "read")).thenReturn(true);
        when(permissions.canAction(30L, "orders", "read")).thenReturn(false);
        AccessDeniedException denied = assertThrows(AccessDeniedException.class, () -> protection.prepare(query, List.of(), "list"));
        assertEquals("Access denied for named query source: orders", denied.getMessage());
    }
    @Test void selfAnchoredPolicyWithoutResolvableUserFailsClosed() {
        var policy = new NamedQueryPolicy();
        policy.setSelfAnchoredSources(true);
        query.setPolicy(policy);
        MetaContext.clear();
        MetaContext.setContext(10L, null, null, null);
        assertThrows(AccessDeniedException.class, () -> protection.prepare(query, List.of(), "list"));
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
    @Test void onlyResolvedPhysicalReadScopesReplaceDeclaredOuterFilters() {
        when(permissions.canAction(eq(30L), anyString(), eq("read"))).thenReturn(true);
        when(policies.buildRowFilter(10L, "orders", "read", 20L)).thenReturn("created_by = 20");
        var plan = protection.prepare(query, List.of(), "list");
        assertTrue(plan.sourceScopes().get("orders").contains("created_by = 20"));
        assertTrue(plan.coversDeclaredScope("orders", "read"));
        assertTrue(plan.coversDeclaredScope("customers", "read"));
        assertFalse(plan.coversDeclaredScope("unrelated", "read"));
        assertFalse(plan.coversDeclaredScope("orders", "export"));
        assertFalse(plan.coversDeclaredScope("orders", null));
        var unverified = new NamedQueryFieldProtection.Plan(plan.evidence(), List.of(), plan.sourceScopes());
        assertFalse(unverified.coversDeclaredScope("orders", "read"));
    }

    @Test void missingMemberUsesCurrentUserWithoutSkippingAuthorization() {
        MetaContext.setMemberId(null);
        when(permissions.canAction(20L, "orders", "read")).thenReturn(true);
        when(permissions.canAction(20L, "customers", "read")).thenReturn(true);
        assertNotNull(protection.prepare(query, List.of()));
        verify(permissions).canAction(20L, "orders", "read");
        verify(permissions).canAction(20L, "customers", "read");
    }

    @Test void missingProtectedColumnMappingCannotSilentlySkipMasking() {
        when(permissions.canAction(eq(30L), anyString(), eq("read"))).thenReturn(true);
        when(policies.getFieldMaskRules(10L, "orders", 20L)).thenReturn(List.of(
                com.auraboot.framework.meta.dto.FieldMaskRule.builder().fieldCode("title").maskType("hide").build()));
        AccessDeniedException denied = assertThrows(AccessDeniedException.class, () -> protection.prepare(query, List.of()));
        assertEquals("Protected field has no physical column mapping", denied.getMessage());
    }

    @Test void collaboratorRootGrantSkipsSourceModelChecksButKeepsTenantScopes() {
        // Regression for the quote record-sharing verification (2026-09-20): a record-scoped
        // collaborator was denied named queries whose fromSql joins sources the collaborator's
        // role has no model-level read on (e.g. qo_supplier_request_line_common), even though
        // the caller holds an update-capable record share on the declared aggregate root and
        // authorizeRootRecord bound the params to exactly that record. The grant must replace
        // the per-source model check; the per-source tenant scopes still apply.
        var plan = protection.prepare(query, List.of(), "list", true);
        assertEquals(Map.of("orders", "(tenant_id = 10)", "customers", "(tenant_id = 10)"), plan.sourceScopes());
        verifyNoInteractions(permissions);
    }

    @Test void collaboratorRootGrantDoesNotLeakIntoThreeArgPrepare() {
        assertThrows(AccessDeniedException.class, () -> protection.prepare(query, List.of(), "list"));
    }
}
