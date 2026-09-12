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
}
