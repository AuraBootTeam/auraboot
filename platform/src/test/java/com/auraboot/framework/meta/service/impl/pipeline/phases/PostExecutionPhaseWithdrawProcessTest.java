package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.bpm.service.BpmIntegrationService;
import com.auraboot.framework.bpm.service.WithdrawService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.CommandSideEffectExecutor;
import com.auraboot.framework.meta.service.impl.CommandSpelEvaluator;
import com.auraboot.framework.meta.service.impl.RollUpFieldRegistry;
import com.auraboot.framework.meta.service.impl.RollUpSummaryService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import com.auraboot.smart.framework.engine.service.query.TaskQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@code withdraw_process} postAction branch in
 * {@link PostExecutionPhase}. End-to-end withdraw semantics (policy gating,
 * initiator check, audit emission) live in
 * {@code WithdrawServiceIntegrationTest}; these tests only assert the
 * dispatcher correctness — record lookup, active-task resolution, error
 * propagation — by mocking the downstream BPM stack.
 *
 * <p>Test layer mirrors the sibling {@link PostExecutionPhaseStartProcessTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostExecutionPhase: withdraw_process postAction")
class PostExecutionPhaseWithdrawProcessTest {

    @Mock private CommandSideEffectExecutor sideEffectExecutor;
    @Mock private RollUpFieldRegistry rollUpFieldRegistry;
    @Mock private RollUpSummaryService rollUpSummaryService;
    @Mock private CommandSpelEvaluator spelEvaluator;
    @Mock private ApplicationContext applicationContext;
    @Mock private DynamicDataService dynamicDataService;
    @Mock private BpmIntegrationService bpmIntegrationService;
    @Mock private WithdrawService withdrawService;
    @Mock private SmartEngine smartEngine;
    @Mock private TaskQueryService taskQueryService;

    private PostExecutionPhase phase;

    @BeforeEach
    void setUp() {
        phase = new PostExecutionPhase(
                sideEffectExecutor, rollUpFieldRegistry, rollUpSummaryService,
                spelEvaluator, applicationContext, new ObjectMapper(), dynamicDataService);
        ReflectionTestUtils.setField(phase, "bpmIntegrationService", bpmIntegrationService);
        ReflectionTestUtils.setField(phase, "withdrawService", withdrawService);
        ReflectionTestUtils.setField(phase, "smartEngine", smartEngine);
        // lenient because some tests fail before SmartEngine is consulted.
        lenient().when(smartEngine.getTaskQueryService()).thenReturn(taskQueryService);
        lenient().when(rollUpFieldRegistry.getTargets(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("Happy path: resolves processInstanceId, picks active task, delegates to WithdrawService")
    void happyPath_delegatesToWithdrawService() {
        // record carries the BPM instance id written earlier by start_process
        Map<String, Object> record = new HashMap<>();
        record.put("wd_req_status", "cancelled");
        record.put("wd_req_process_instance", "pi-100");
        when(dynamicDataService.getById("wd_leave_request", "rec-7")).thenReturn(record);

        TaskInstance pendingTask = org.mockito.Mockito.mock(TaskInstance.class);
        when(pendingTask.getInstanceId()).thenReturn("task-200");
        when(taskQueryService.findAllPendingTaskList(eq("pi-100"), eq("1")))
                .thenReturn(List.of(pendingTask));

        Map<String, Object> postAction = new HashMap<>();
        postAction.put("type", "withdraw_process");
        postAction.put("reason", "Cancelled by ${recordId}");

        CommandPipelineContext ctx = buildCtx(postAction, "rec-7", "wd_leave_request");

        phase.execute(ctx);

        // Reason template resolved with parentRecordId substitution
        verify(withdrawService).withdraw(eq("task-200"), eq("Cancelled by rec-7"));
    }

    @Test
    @DisplayName("Default reason 'Cancelled by initiator' when reason field absent")
    void happyPath_defaultReasonWhenAbsent() {
        Map<String, Object> record = new HashMap<>();
        record.put("wd_req_process_instance", "pi-101");
        when(dynamicDataService.getById("wd_leave_request", "rec-8")).thenReturn(record);

        TaskInstance pendingTask = org.mockito.Mockito.mock(TaskInstance.class);
        when(pendingTask.getInstanceId()).thenReturn("task-201");
        when(taskQueryService.findAllPendingTaskList(eq("pi-101"), eq("1")))
                .thenReturn(List.of(pendingTask));

        Map<String, Object> postAction = new HashMap<>();
        postAction.put("type", "withdraw_process");

        CommandPipelineContext ctx = buildCtx(postAction, "rec-8", "wd_leave_request");

        phase.execute(ctx);

        verify(withdrawService).withdraw(eq("task-201"), eq("Cancelled by initiator"));
    }

    @Test
    @DisplayName("Custom instanceIdField is honoured")
    void happyPath_customInstanceIdField() {
        Map<String, Object> record = new HashMap<>();
        record.put("custom_pid_col", "pi-555");
        when(dynamicDataService.getById("custom_model", "rec-9")).thenReturn(record);

        TaskInstance pendingTask = org.mockito.Mockito.mock(TaskInstance.class);
        when(pendingTask.getInstanceId()).thenReturn("task-555");
        when(taskQueryService.findAllPendingTaskList(eq("pi-555"), eq("1")))
                .thenReturn(List.of(pendingTask));

        Map<String, Object> postAction = new HashMap<>();
        postAction.put("type", "withdraw_process");
        postAction.put("instanceIdField", "custom_pid_col");

        CommandPipelineContext ctx = buildCtx(postAction, "rec-9", "custom_model");

        phase.execute(ctx);

        verify(withdrawService).withdraw(eq("task-555"), any());
    }

    @Test
    @DisplayName("Throws when record is missing the processInstanceId field value")
    void throwsWhenInstanceIdFieldEmpty() {
        Map<String, Object> record = new HashMap<>();
        record.put("wd_req_status", "cancelled");
        // wd_req_process_instance intentionally absent
        when(dynamicDataService.getById("wd_leave_request", "rec-10")).thenReturn(record);

        Map<String, Object> postAction = new HashMap<>();
        postAction.put("type", "withdraw_process");

        CommandPipelineContext ctx = buildCtx(postAction, "rec-10", "wd_leave_request");

        assertThatThrownBy(() -> phase.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("process instance not found")
                .hasMessageContaining("wd_req_process_instance");

        verify(withdrawService, never()).withdraw(any(), any());
    }

    @Test
    @DisplayName("Throws when record lookup returns null")
    void throwsWhenRecordNotFound() {
        when(dynamicDataService.getById("wd_leave_request", "rec-missing")).thenReturn(null);

        Map<String, Object> postAction = new HashMap<>();
        postAction.put("type", "withdraw_process");

        CommandPipelineContext ctx = buildCtx(postAction, "rec-missing", "wd_leave_request");

        assertThatThrownBy(() -> phase.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("record not found");

        verify(withdrawService, never()).withdraw(any(), any());
    }

    @Test
    @DisplayName("Throws when SmartEngine has no pending tasks for the instance")
    void throwsWhenNoActiveTask() {
        Map<String, Object> record = new HashMap<>();
        record.put("wd_req_process_instance", "pi-terminated");
        when(dynamicDataService.getById("wd_leave_request", "rec-11")).thenReturn(record);

        when(taskQueryService.findAllPendingTaskList(eq("pi-terminated"), eq("1")))
                .thenReturn(List.of());

        Map<String, Object> postAction = new HashMap<>();
        postAction.put("type", "withdraw_process");

        CommandPipelineContext ctx = buildCtx(postAction, "rec-11", "wd_leave_request");

        assertThatThrownBy(() -> phase.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no active task")
                .hasMessageContaining("pi-terminated");

        verify(withdrawService, never()).withdraw(any(), any());
    }

    @Test
    @DisplayName("Propagates WithdrawService BusinessException (initiator/policy gating)")
    void propagatesWithdrawServiceException() {
        Map<String, Object> record = new HashMap<>();
        record.put("wd_req_process_instance", "pi-200");
        when(dynamicDataService.getById("wd_leave_request", "rec-12")).thenReturn(record);

        TaskInstance pendingTask = org.mockito.Mockito.mock(TaskInstance.class);
        when(pendingTask.getInstanceId()).thenReturn("task-300");
        when(taskQueryService.findAllPendingTaskList(eq("pi-200"), eq("1")))
                .thenReturn(List.of(pendingTask));

        // Simulate WithdrawService rejecting the call (e.g. non-initiator,
        // strict policy after approval, or NONE policy). The exception MUST
        // propagate so the outer command transaction rolls back the
        // state_transition pre-write.
        org.mockito.Mockito.doThrow(new BusinessException("Only the initiator can withdraw this process"))
                .when(withdrawService).withdraw(eq("task-300"), any());

        Map<String, Object> postAction = new HashMap<>();
        postAction.put("type", "withdraw_process");

        CommandPipelineContext ctx = buildCtx(postAction, "rec-12", "wd_leave_request");

        assertThatThrownBy(() -> phase.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("initiator");
    }

    // ---------- helpers ----------

    private CommandPipelineContext buildCtx(Map<String, Object> postAction, String recordId, String modelCode) {
        Map<String, Object> execConfig = new HashMap<>();
        execConfig.put("postActions", List.of(postAction));

        CommandDefinition command = new CommandDefinition();
        command.setModelCode(modelCode);

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordId(recordId);

        return CommandPipelineContext.builder()
                .execConfig(execConfig)
                .payload(new HashMap<>())
                .command(command)
                .request(request)
                .tenantId(1L)
                .userId(1L)
                .build();
    }
}
