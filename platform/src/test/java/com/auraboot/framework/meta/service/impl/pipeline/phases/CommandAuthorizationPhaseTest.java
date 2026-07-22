package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommandAuthorizationPhaseTest {

    @Mock
    private UserPermissionService userPermissionService;

    @Test
    void executeSkipsPermissionServiceWhenCommandDeclaresNoPermissions() {
        CommandAuthorizationPhase phase = new CommandAuthorizationPhase(userPermissionService);

        phase.execute(contextWithPermissions(null, 42L));

        verifyNoInteractions(userPermissionService);
    }

    @Test
    void executeRecordsWhichPermissionAuthorizedTheCaller() {
        CommandAuthorizationPhase phase = new CommandAuthorizationPhase(userPermissionService);
        when(userPermissionService.hasPermission(42L, "dashboard.manage")).thenReturn(true);
        CommandPipelineContext ctx = contextWithPermissions(List.of("dashboard.manage"), 42L);

        phase.execute(ctx);

        assertThat(ctx.getAuthorizationVerdict().isAuthorized()).isTrue();
        assertThat(ctx.getAuthorizationVerdict().permissionCode()).isEqualTo("dashboard.manage");
    }

    /**
     * A command that declares nothing has granted nothing. Downstream stages may only inherit the
     * boundary's authority from an AUTHORIZED verdict, so this must be distinguishable from one —
     * and it must never read as authorized just because nothing was thrown.
     */
    @Test
    void executeRecordsThatNoDecisionWasMadeWhenNothingIsDeclared() {
        CommandAuthorizationPhase phase = new CommandAuthorizationPhase(userPermissionService);
        CommandPipelineContext ctx = contextWithPermissions(null, 42L);

        phase.execute(ctx);

        assertThat(ctx.getAuthorizationVerdict().isAuthorized()).isFalse();
        assertThat(ctx.getAuthorizationVerdict().reason())
                .isEqualTo(CommandAuthorizationVerdict.REASON_NO_DECLARED_PERMISSIONS);
    }

    @Test
    void executeRecordsThatNoDecisionWasMadeWithoutAUserInContext() {
        CommandAuthorizationPhase phase = new CommandAuthorizationPhase(userPermissionService);
        CommandPipelineContext ctx = contextWithPermissions(List.of("dashboard.manage"), null);

        phase.execute(ctx);

        assertThat(ctx.getAuthorizationVerdict().isAuthorized()).isFalse();
        assertThat(ctx.getAuthorizationVerdict().reason())
                .isEqualTo(CommandAuthorizationVerdict.REASON_NO_USER_CONTEXT);
        verifyNoInteractions(userPermissionService);
    }

    @Test
    void executeAllowsCommandWhenUserHasAnyDeclaredPermission() {
        CommandAuthorizationPhase phase = new CommandAuthorizationPhase(userPermissionService);
        when(userPermissionService.hasPermission(42L, "dashboard.manage")).thenReturn(true);

        phase.execute(contextWithPermissions(List.of("dashboard.manage", "dashboard.admin"), 42L));

        verify(userPermissionService).hasPermission(42L, "dashboard.manage");
        verify(userPermissionService, never()).hasPermission(42L, "dashboard.admin");
    }

    @Test
    void executeDeniesCommandWhenUserHasNoneOfTheDeclaredPermissions() {
        CommandAuthorizationPhase phase = new CommandAuthorizationPhase(userPermissionService);
        when(userPermissionService.hasPermission(42L, "dashboard.manage")).thenReturn(false);
        when(userPermissionService.hasPermission(42L, "dashboard.admin")).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                phase.execute(
                                        contextWithPermissions(
                                                List.of("dashboard.manage", "dashboard.admin"),
                                                42L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Command permission denied")
                .extracting("responseCode")
                .isEqualTo(ResponseCode.FORBIDDEN);

        verify(userPermissionService).hasPermission(42L, "dashboard.manage");
        verify(userPermissionService).hasPermission(42L, "dashboard.admin");
    }

    @Test
    void executeIgnoresBlankPermissionEntriesBeforeCheckingAccess() {
        CommandAuthorizationPhase phase = new CommandAuthorizationPhase(userPermissionService);
        when(userPermissionService.hasPermission(42L, "dashboard.manage")).thenReturn(true);

        phase.execute(contextWithPermissions(List.of("", "  ", "dashboard.manage"), 42L));

        verify(userPermissionService).hasPermission(42L, "dashboard.manage");
        verify(userPermissionService, never()).hasPermission(42L, "");
        verify(userPermissionService, never()).hasPermission(42L, "  ");
    }

    private CommandPipelineContext contextWithPermissions(Object permissions, Long userId) {
        Map<String, Object> execConfig = new HashMap<>();
        if (permissions != null) {
            execConfig.put("permissions", permissions);
        }

        return CommandPipelineContext.builder()
                .commandCode("dashboard.export")
                .request(new CommandExecuteRequest())
                .tenantId(1L)
                .userId(userId)
                .startTime(System.currentTimeMillis())
                .execConfig(execConfig)
                .build();
    }
}
