package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.permission.service.PermissionFacade;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Numeric lookup must retain native query and canonical PID authorization. */
class RecordReadAccessNumericIdentityTest {
    private final DynamicDataService data = mock(DynamicDataService.class);
    private final PermissionFacade permissions = mock(PermissionFacade.class);
    private final RecordReadAccess reads = new RecordReadAccess(data, permissions,
            mock(TenantMemberService.class), mock(UserRoleService.class), mock(UserService.class));

    @BeforeEach void actor() { MetaContext.setContext(1L, 2L, "actor", "Actor"); MetaContext.setMemberId(3L); }
    @AfterEach void clear() { MetaContext.clear(); }

    @Test void resolvedPidGoesThroughTheOriginalReadBoundary() {
        when(permissions.canAction(3L, "invoice", "read")).thenReturn(true);
        when(data.list(eq("invoice"), any())).thenReturn(PaginationResult.of(
                List.of(Map.of("id", 41L, "pid", "canonical-pid")), 1L, 1, 2));
        when(data.getById("invoice", "canonical-pid")).thenReturn(Map.of("pid", "canonical-pid"));
        assertThat(reads.requireReadableByNumericId("invoice", 41L)).isEqualTo("canonical-pid");
        var request = ArgumentCaptor.forClass(DynamicQueryRequest.class);
        verify(data).list(eq("invoice"), request.capture());
        assertThat(request.getValue().getPageSize()).isEqualTo(2);
        assertThat(request.getValue().getConditions()).singleElement().satisfies(condition -> {
            assertThat(condition.getFieldName()).isEqualTo("id");
            assertThat(condition.getOperator()).isEqualTo(QueryCondition.Operator.EQ);
            assertThat(condition.getValue()).isEqualTo(41L);
        });
        verify(data).getById("invoice", "canonical-pid");
        verify(data, never()).getById("invoice", "41");
    }

    @Test void missingPermissionNeverQueriesBusinessRows() {
        assertThatThrownBy(() -> reads.requireReadableByNumericId("invoice", 41L))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(data);
    }

    @Test void absentAmbiguousAndMalformedIdentitiesCannotAuthorizeAWatch() {
        when(permissions.canAction(3L, "invoice", "read")).thenReturn(true);
        for (List<Map<String,Object>> records : List.<List<Map<String,Object>>>of(
                List.of(), List.of(Map.of("pid", "a"), Map.of("pid", "b")),
                List.of(Map.of("id", 41L)), List.of(Map.of("pid", "")))) {
            when(data.list(eq("invoice"), any())).thenReturn(PaginationResult.of(records, (long) records.size(), 1, 2));
            assertThatThrownBy(() -> reads.requireReadableByNumericId("invoice", 41L))
                    .isInstanceOf(AccessDeniedException.class);
        }
        verify(data, never()).getById(anyString(), anyString());
    }

    @Test void revocationAtCanonicalReadPropagatesInsteadOfTrustingTheLookup() {
        when(permissions.canAction(3L, "invoice", "read")).thenReturn(true);
        when(data.list(eq("invoice"), any())).thenReturn(PaginationResult.of(List.of(Map.of("pid", "a")), 1L, 1, 2));
        when(data.getById("invoice", "a")).thenThrow(new AccessDeniedException("revoked"));
        assertThatThrownBy(() -> reads.requireReadableByNumericId("invoice", 41L))
                .isInstanceOf(AccessDeniedException.class).hasMessage("revoked");
    }
}
