package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.bpm.service.BpmIntegrationService;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.CommandSideEffectExecutor;
import com.auraboot.framework.meta.service.impl.CommandSpelEvaluator;
import com.auraboot.framework.meta.service.impl.RollUpFieldRegistry;
import com.auraboot.framework.meta.service.impl.RollUpSummaryService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the new {@code start_process} postAction branch in {@link PostExecutionPhase}.
 */
@ExtendWith(MockitoExtension.class)
class PostExecutionPhaseStartProcessTest {

    @Mock private CommandSideEffectExecutor sideEffectExecutor;
    @Mock private RollUpFieldRegistry rollUpFieldRegistry;
    @Mock private RollUpSummaryService rollUpSummaryService;
    @Mock private CommandSpelEvaluator spelEvaluator;
    @Mock private ApplicationContext applicationContext;
    @Mock private DynamicDataService dynamicDataService;
    @Mock private BpmIntegrationService bpmIntegrationService;

    private PostExecutionPhase phase;

    @BeforeEach
    void setUp() {
        phase = new PostExecutionPhase(
                sideEffectExecutor, rollUpFieldRegistry, rollUpSummaryService,
                spelEvaluator, applicationContext, new ObjectMapper(), dynamicDataService);
        ReflectionTestUtils.setField(phase, "bpmIntegrationService", bpmIntegrationService);
        when(rollUpFieldRegistry.getTargets(any())).thenReturn(List.of());
    }

    @Test
    void startProcess_callsBpmIntegrationAndStoresInstanceId() {
        ProcessInstance instance = org.mockito.Mockito.mock(ProcessInstance.class);
        when(instance.getInstanceId()).thenReturn("proc-42");
        when(bpmIntegrationService.startBusinessProcess(
                eq("wd_leave_approval"), eq("rec-7"), any(), eq("WDLR-20260415-001")))
                .thenReturn(instance);

        Map<String, Object> postAction = new HashMap<>();
        postAction.put("type", "start_process");
        postAction.put("processKey", "wd_leave_approval");
        postAction.put("businessKey", "${recordId}");
        postAction.put("title", "${payload.wd_req_code}");
        Map<String, Object> variables = new HashMap<>();
        variables.put("days", "${payload.wd_req_days}");
        variables.put("recordId", "${recordId}");
        postAction.put("variables", variables);
        postAction.put("storeInstanceIdIn", "wd_req_process_instance");

        Map<String, Object> execConfig = new HashMap<>();
        execConfig.put("postActions", List.of(postAction));

        Map<String, Object> payload = new HashMap<>();
        payload.put("wd_req_code", "WDLR-20260415-001");
        payload.put("wd_req_days", 3);

        CommandDefinition command = new CommandDefinition();
        command.setModelCode("wd_leave_request");

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordId("rec-7");

        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .execConfig(execConfig)
                .payload(payload)
                .command(command)
                .request(request)
                .tenantId(1L)
                .userId(1L)
                .build();

        phase.execute(ctx);

        ArgumentCaptor<Map<String, Object>> varsCaptor = ArgumentCaptor.forClass((Class) Map.class);
        verify(bpmIntegrationService).startBusinessProcess(
                eq("wd_leave_approval"), eq("rec-7"), varsCaptor.capture(), eq("WDLR-20260415-001"));
        Map<String, Object> sent = varsCaptor.getValue();
        assertThat(sent).containsEntry("days", "3");
        assertThat(sent).containsEntry("recordId", "rec-7");

        // storeInstanceIdIn triggers an update on the record.
        ArgumentCaptor<Map<String, Object>> upd = ArgumentCaptor.forClass((Class) Map.class);
        verify(dynamicDataService).update(eq("wd_leave_request"), eq("rec-7"), upd.capture());
        assertThat(upd.getValue()).containsEntry("wd_req_process_instance", "proc-42");
    }
}
