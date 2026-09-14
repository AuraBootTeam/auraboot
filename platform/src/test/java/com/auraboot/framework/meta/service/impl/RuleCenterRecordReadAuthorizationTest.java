package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.PermissionFacade;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuleCenterRecordReadAuthorizationTest {
    private final PermissionFacade facade = mock(PermissionFacade.class);
    private final Map<String, Object> record = Map.of("pid", "record", "owner", 7L);

    @Test void grantsOnlyTheResolvedMembersReadOnTheOriginalRecord() {
        var verdict = mock(PermissionResult.class);
        when(verdict.granted()).thenReturn(true);
        when(facade.canOperate(7L, "orders", "read", record)).thenReturn(verdict);
        assertThatCode(() -> check(7L)).doesNotThrowAnyException();
        verify(facade).canOperate(7L, "orders", "read", record);
    }
    @Test void missingMemberDoesNotResolveOrCallTheFacade() {
        assertThatThrownBy(() -> RuleCenterRecordReadAuthorization.requireReadable("orders", record,
                () -> null, () -> { throw new AssertionError("Facade must not be resolved"); }))
                .isInstanceOf(MetaServiceException.class).hasMessageContaining("Permission context missing");
    }
    @Test void unavailableFacadeRejects() {
        assertThatThrownBy(() -> RuleCenterRecordReadAuthorization.requireReadable("orders", record,
                () -> 7L, () -> null)).isInstanceOf(MetaServiceException.class);
    }
    @Test void deniedVerdictRejects() {
        when(facade.canOperate(7L, "orders", "read", record)).thenReturn(mock(PermissionResult.class));
        assertThatThrownBy(() -> check(7L)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void absentVerdictRejects() {
        assertThatThrownBy(() -> check(7L)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void evaluationFailurePropagatesWithoutAllowingAccess() {
        var failure = new IllegalStateException("policy unavailable");
        when(facade.canOperate(7L, "orders", "read", record)).thenThrow(failure);
        assertThatThrownBy(() -> check(7L)).isSameAs(failure);
    }
    private void check(Long member) {
        RuleCenterRecordReadAuthorization.requireReadable("orders", record, () -> member, () -> facade);
    }
}
