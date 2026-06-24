package com.auraboot.framework.automation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Automation pid DTO public contract")
class AutomationPidDtoContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void automationLogDtoSerializesTriggerRecordPidOnly() throws Exception {
        AutomationLogDTO dto = AutomationLogDTO.builder()
                .pid("log-pid")
                .triggerRecordPid("record-pid-1")
                .build();

        JsonNode json = objectMapper.valueToTree(dto);

        assertThat(json.has("triggerRecordPid")).isTrue();
        assertThat(json.get("triggerRecordPid").asText()).isEqualTo("record-pid-1");
        assertThat(json.has("triggerRecord" + "Id")).isFalse();
    }

    @Test
    void debugSessionCreateRequestHasRecordPidPublicField() throws Exception {
        Field field = DebugSessionCreateRequest.class.getDeclaredField("recordPid");

        assertThat(field.getType()).isEqualTo(String.class);
    }

    @Test
    void debugSessionDtoSerializesRecordPidOnly() throws Exception {
        DebugSessionDTO dto = DebugSessionDTO.builder()
                .pid("session-pid")
                .recordPid("record-pid-1")
                .build();

        JsonNode json = objectMapper.valueToTree(dto);

        assertThat(json.has("recordPid")).isTrue();
        assertThat(json.get("recordPid").asText()).isEqualTo("record-pid-1");
        assertThat(json.has("record" + "Id")).isFalse();
    }
}
