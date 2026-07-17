package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.dto.ExecutionLogEntry;
import com.auraboot.framework.bpm.dto.ProcessInstanceStatusDTO;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.service.query.ProcessQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessEngineServiceTest {

    @Mock
    private SmartEngine smartEngine;

    @Mock
    private ProcessQueryService processQueryService;

    @Mock
    private BpmAuditService bpmAuditService;

    @Mock
    private SlaRecordService slaRecordService;

    @Mock
    private BpmProcessDefinitionMapper processDefinitionMapper;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ExecutionLogService executionLogService;

    private ProcessEngineService service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(1001L, 2002L, "admin-pid", "admin");
        when(smartEngine.getProcessQueryService()).thenReturn(processQueryService);
        service = new ProcessEngineService(
                smartEngine,
                bpmAuditService,
                slaRecordService,
                processDefinitionMapper,
                jdbcTemplate,
                executionLogService);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void getProcessInstanceStatusReturnsFailureSnapshotWhenEngineInstanceIsMissing() {
        when(processQueryService.findById("exec-1", "1001")).thenReturn(null);
        when(executionLogService.getLatestFailure("exec-1", 1001L)).thenReturn(actionFailureEntry());

        ProcessInstanceStatusDTO status = service.getProcessInstanceStatus("exec-1");

        assertThat(status).isNotNull();
        assertThat(status.instanceId()).isEqualTo("exec-1");
        assertThat(status.processDefinitionId()).isEqualTo("bpm_action_fail");
        assertThat(status.status()).isEqualTo("terminated");
        assertThat(status.startUserId()).isEqualTo("starter-1");
        assertThat(status.currentNodes()).singleElement().satisfies(node -> {
            assertThat(node.nodeId()).isEqualTo("sms_action");
            assertThat(node.type()).isEqualTo("serviceTask");
            assertThat(node.status()).isEqualTo("failed");
        });
        assertThat(status.variables())
                .containsEntry("businessKey", "REQ-SMS-1")
                .containsEntry("_action_sms_action_success", false)
                .containsKey("_action_sms_action_result");
    }

    @Test
    void getProcessInstanceStatusByBusinessKeyReturnsFailureSnapshotWhenStartRolledBackInstance() {
        when(processQueryService.findList(any())).thenReturn(List.of());
        when(executionLogService.getLatestFailureByBusinessKey(1001L, "bpm_action_fail", "REQ-SMS-1"))
                .thenReturn(actionFailureEntry());

        ProcessInstanceStatusDTO status =
                service.getProcessInstanceStatusByBusinessKey("bpm_action_fail", "REQ-SMS-1");

        assertThat(status).isNotNull();
        assertThat(status.instanceId()).isEqualTo("exec-1");
        assertThat(status.processDefinitionId()).isEqualTo("bpm_action_fail");
        assertThat(status.currentNodes()).extracting("status").containsExactly("failed");
    }

    private ExecutionLogEntry actionFailureEntry() {
        return new ExecutionLogEntry(
                "01FAIL",
                "exec-1",
                "sms_action",
                null,
                "node_failure",
                Map.of(
                        "processKey", "bpm_action_fail",
                        "businessKey", "REQ-SMS-1",
                        "startUserId", "starter-1",
                        "action", Map.of(
                                "status", "FAILED",
                                "actionType", "SEND_SMS",
                                "channel", "sms",
                                "sentCount", 0)),
                null,
                "No real SMS sender available",
                null,
                Instant.parse("2026-07-17T01:02:03Z"));
    }
}
