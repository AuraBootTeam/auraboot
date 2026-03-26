package com.auraboot.framework.meta.handler;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.tenant.service.TenantMemberApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TenantMemberCommandHandler.
 */
@ExtendWith(MockitoExtension.class)
class TenantMemberCommandHandlerTest {

    @Mock
    private TenantMemberApplicationService tenantMemberApplicationService;

    @InjectMocks
    private TenantMemberCommandHandler handler;

    // =========================================================
    // getHandlerName
    // =========================================================

    @Test
    void getHandlerName_returnsTenantMemberCommandHandler() {
        assertThat(handler.getHandlerName()).isEqualTo("tenantMemberCommandHandler");
    }

    // =========================================================
    // approve_member
    // =========================================================

    @Test
    void execute_approveMember_callsServiceAndReturnsActive() {
        CommandHandlerContext ctx = buildContext("admin:approve_member", "mbr-001", 100L, null);

        Map<String, Object> result = handler.execute(ctx);

        assertThat(result.get("handlerExecuted")).isEqualTo(true);
        assertThat(result.get("action")).isEqualTo("approve");
        assertThat(result.get("newStatus")).isEqualTo("active");
        verify(tenantMemberApplicationService).approveMember("mbr-001", "approve", null, 100L);
    }

    // =========================================================
    // reject_member
    // =========================================================

    @Test
    void execute_rejectMember_withReason_callsServiceAndReturnsRejected() {
        CommandHandlerContext ctx = buildContext("admin:reject_member", "mbr-002", 100L,
                Map.of("reason", "Duplicate account"));

        Map<String, Object> result = handler.execute(ctx);

        assertThat(result.get("action")).isEqualTo("reject");
        assertThat(result.get("newStatus")).isEqualTo("rejected");
        assertThat(result.get("reason")).isEqualTo("Duplicate account");
        verify(tenantMemberApplicationService).approveMember("mbr-002", "reject", "Duplicate account", 100L);
    }

    @Test
    void execute_rejectMember_noReason_noReasonInResult() {
        CommandHandlerContext ctx = buildContext("admin:reject_member", "mbr-003", 100L, null);

        Map<String, Object> result = handler.execute(ctx);

        assertThat(result.get("action")).isEqualTo("reject");
        assertThat(result).doesNotContainKey("reason");
    }

    // =========================================================
    // suspend_member
    // =========================================================

    @Test
    void execute_suspendMember_withReason_returnsCorrectStatus() {
        CommandHandlerContext ctx = buildContext("admin:suspend_member", "mbr-004", 100L,
                Map.of("reason", "Policy violation"));

        Map<String, Object> result = handler.execute(ctx);

        assertThat(result.get("action")).isEqualTo("suspend");
        assertThat(result.get("newStatus")).isEqualTo("suspended");
        assertThat(result.get("reason")).isEqualTo("Policy violation");
        verify(tenantMemberApplicationService).updateMemberStatus("mbr-004", "suspended", "Policy violation", 100L);
    }

    // =========================================================
    // restore_member
    // =========================================================

    @Test
    void execute_restoreMember_callsApproveAndReturnsActive() {
        CommandHandlerContext ctx = buildContext("admin:restore_member", "mbr-005", 100L, null);

        Map<String, Object> result = handler.execute(ctx);

        assertThat(result.get("action")).isEqualTo("restore");
        assertThat(result.get("newStatus")).isEqualTo("active");
        verify(tenantMemberApplicationService).approveMember("mbr-005", "approve", null, 100L);
    }

    // =========================================================
    // leave_member
    // =========================================================

    @Test
    void execute_leaveMember_setsInactive() {
        CommandHandlerContext ctx = buildContext("admin:leave_member", "mbr-006", 100L, null);

        Map<String, Object> result = handler.execute(ctx);

        assertThat(result.get("action")).isEqualTo("leave");
        assertThat(result.get("newStatus")).isEqualTo("inactive");
        verify(tenantMemberApplicationService).updateMemberStatus("mbr-006", "inactive", null, 100L);
    }

    // =========================================================
    // delete_member
    // =========================================================

    @Test
    void execute_deleteMember_callsRemoveAndSetsRemoved() {
        CommandHandlerContext ctx = buildContext("admin:delete_member", "mbr-007", 100L, null);

        Map<String, Object> result = handler.execute(ctx);

        assertThat(result.get("action")).isEqualTo("delete");
        assertThat(result.get("removed")).isEqualTo(true);
        verify(tenantMemberApplicationService).removeMember("mbr-007", 100L);
    }

    // =========================================================
    // Unknown command
    // =========================================================

    @Test
    void execute_unknownCommand_returnsHandlerExecutedFalse() {
        CommandHandlerContext ctx = buildContext("admin:unknown_op", "mbr-008", 100L, null);

        Map<String, Object> result = handler.execute(ctx);

        assertThat(result.get("handlerExecuted")).isEqualTo(false);
        verifyNoInteractions(tenantMemberApplicationService);
    }

    // =========================================================
    // Validation errors
    // =========================================================

    @Test
    void execute_missingMemberPid_throwsBusinessException() {
        CommandHandlerContext ctx = CommandHandlerContext.builder()
                .commandCode("admin:approve_member")
                .targetRecordId(null)
                .payload(null)
                .userId(100L)
                .build();

        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("memberPid");
    }

    @Test
    void execute_missingUserId_throwsBusinessException() {
        CommandHandlerContext ctx = CommandHandlerContext.builder()
                .commandCode("admin:approve_member")
                .targetRecordId("mbr-001")
                .userId(null)
                .build();

        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("User ID");
    }

    @Test
    void execute_memberPidFromPayload_whenTargetRecordIdBlank() {
        CommandHandlerContext ctx = CommandHandlerContext.builder()
                .commandCode("admin:approve_member")
                .targetRecordId("  ")
                .payload(Map.of("pid", "payload-pid-001"))
                .userId(100L)
                .build();

        handler.execute(ctx);

        verify(tenantMemberApplicationService).approveMember("payload-pid-001", "approve", null, 100L);
    }

    // =========================================================
    // Service exception wrapped in BusinessException
    // =========================================================

    @Test
    void execute_serviceThrowsRuntimeException_wrappedAsBusinessException() {
        doThrow(new RuntimeException("DB error"))
                .when(tenantMemberApplicationService).approveMember(any(), any(), any(), any());

        CommandHandlerContext ctx = buildContext("admin:approve_member", "mbr-999", 100L, null);

        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("DB error");
    }

    // =========================================================
    // Helper
    // =========================================================

    private CommandHandlerContext buildContext(String commandCode, String targetRecordId,
                                               Long userId, Map<String, Object> payload) {
        return CommandHandlerContext.builder()
                .commandCode(commandCode)
                .targetRecordId(targetRecordId)
                .userId(userId)
                .payload(payload)
                .build();
    }
}
