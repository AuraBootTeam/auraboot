package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.dto.replay.AgentActionItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("Agent run replay pid-only public contract")
class AgentRunQuerySupportPidContractTest {

    @Test
    void resultContractExposesTargetRecordPidOnly() {
        AgentRunQuerySupport support = new AgentRunQuerySupport(mock(JdbcTemplate.class), new ObjectMapper());
        AgentActionItem action = AgentActionItem.builder()
                .pid("action-pid")
                .actionCode("crm.account.update")
                .actionType("update")
                .targetModel("crm_account")
                .targetRecordPid("record-pid-1")
                .actionStatus("success")
                .build();

        Map<String, Object> data = support.buildResultContracts(java.util.List.of(action))
                .get(0)
                .getContract();
        @SuppressWarnings("unchecked")
        Map<String, Object> contractData = (Map<String, Object>) data.get("data");

        assertThat(contractData)
                .containsEntry("targetRecordPid", "record-pid-1")
                .doesNotContainKey("targetRecordId");
    }
}
