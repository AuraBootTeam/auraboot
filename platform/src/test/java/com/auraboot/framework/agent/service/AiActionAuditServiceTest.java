package com.auraboot.framework.agent.service;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI action audit service")
class AiActionAuditServiceTest {

    @Mock private DynamicDataMapper dynamicDataMapper;

    private AiActionAuditService service;

    @BeforeEach
    void setUp() {
        service = new AiActionAuditService(dynamicDataMapper, new ObjectMapper());
    }

    @Test
    @DisplayName("query logs exposes targetPid while keeping legacy record_id")
    void queryLogsExposesTargetPidAlias() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1L);
        row.put("record_id", "REC-PID-001");
        row.put("model_code", "crm_account");
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                .thenReturn(List.of(row));

        List<Map<String, Object>> logs = service.queryLogs(7L, 1, 20);

        assertThat(logs).hasSize(1);
        assertThat(logs.get(0))
                .containsEntry("record_id", "REC-PID-001")
                .containsEntry("targetPid", "REC-PID-001");
    }
}
