package com.auraboot.framework.agent.service;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.event.AuraEventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentResumeGrantTest {
    private final DynamicDataMapper data = mock(DynamicDataMapper.class);
    private final AgentApprovalGateService gate = new AgentApprovalGateService(data, new ObjectMapper(),
            mock(AuraEventBus.class), mock(AgentDispatchHandler.class));

    @Test void exactGrantClaimRequiresOneAffectedRowAndBindsItsPayload() throws Exception {
        when(data.updateByQuery(anyString(), anyMap())).thenReturn(1, 0);
        Map<String, Object> input = Map.of("customer", "one", "amount", 4);
        assertThat(gate.consumeResumeGrant(7L, "task", "approval", "write", input)).isTrue();
        assertThat(gate.consumeResumeGrant(7L, "task", "approval", "write", input)).isFalse();
        ArgumentCaptor<String> statement = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(data, times(2)).updateByQuery(statement.capture(), params.capture());
        assertThat(statement.getValue()).contains("approval_status='approved'", "consumed_at IS NULL",
                "expires_at > NOW()", "plan_hash=#{params.planHash}", "request_data::jsonb", "plan_snapshot::jsonb",
                "pid=#{params.approvalPid}", "FOR UPDATE SKIP LOCKED");
        assertThat(params.getValue()).containsEntry("tenantId", 7L).containsEntry("taskId", "task")
                .containsEntry("approvalPid", "approval").containsEntry("toolDesc", "Tool: write");
        assertThat(new ObjectMapper().readTree((String) params.getValue().get("payload")))
                .isEqualTo(new ObjectMapper().valueToTree(input));
    }
    @Test void missingScopeCannotClaimAnyApproval() {
        assertThat(gate.consumeResumeGrant(null, "task", "approval", "write", Map.of())).isFalse();
        assertThat(gate.consumeResumeGrant(7L, null, "approval", "write", Map.of())).isFalse();
        verifyNoInteractions(data);
    }
    @Test void requiredApprovalWithoutPolicyDeniesInsteadOfReturningPermission() {
        when(data.selectByQuery(anyString(), anyMap())).thenReturn(java.util.List.of());
        assertThatThrownBy(() -> gate.checkAndRequestApproval(7L, "run", "task", "write", "Write", Map.of(), true))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessageContaining("no matching policy");
        verify(data, never()).updateByQuery(anyString(), anyMap());
        verify(data, never()).insert(anyString(), anyMap());
    }

    @Test void ordinaryToolGateClaimsOnlyTheCurrentPayload() {
        when(data.selectByQuery(anyString(), anyMap())).thenReturn(java.util.List.of(Map.of(
                "pid", "policy", "trigger_rules", "[{\"type\":\"tool_call\",\"pattern\":\"write\"}]")));
        when(data.updateByQuery(anyString(), anyMap())).thenReturn(1);
        var input = Map.<String, Object>of("recipient", "current");
        assertThat(gate.checkAndRequestApproval(7L, "run", "task", "write", "Write", input, true)).isNull();
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(data).updateByQuery(contains("plan_snapshot::jsonb"), params.capture());
        assertThat(params.getValue()).containsEntry("payload", "{\"recipient\":\"current\"}");
        verify(data, never()).insert(anyString(), anyMap());
    }

    @Test void stringApproverIdsMatchExactlyWithoutFloatingPointCoercion() {
        long userId = 357021799806013440L;
        for (String encoded : new String[]{"\"357021799806013440\"", "357021799806013440", "357021799806013440.5", "\"9223372036854775808\"", "\"invalid\""}) {
            reset(data);
            when(data.selectByQuery(anyString(), anyMap())).thenReturn(
                    java.util.List.of(Map.of("pid", "approval", "policy_id", "policy")),
                    java.util.List.of(Map.of("pid", "policy", "approver_rules", "[{\"type\":\"USER\",\"userId\":" + encoded + "}]")));
            assertThat(gate.isAuthorizedApprover(7L, "approval", userId))
                    .as("rule value %s", encoded)
                    .isEqualTo(encoded.equals("\"357021799806013440\"") || encoded.equals("357021799806013440"));
        }
    }

}
