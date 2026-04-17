package com.auraboot.framework.bpm;

import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.framework.bpm.dto.FormBindingConfig;
import com.auraboot.framework.bpm.dto.ProcessStartRequest;
import com.auraboot.framework.bpm.dto.TaskActionDef;
import com.auraboot.framework.bpm.dto.TaskSubmitRequest;
import com.auraboot.framework.bpm.enums.SaveStrategy;
import com.auraboot.framework.bpm.service.BpmFormService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for BpmFormService SaveStrategy-aware form submission.
 * Tests buildProcessVariables logic, deriveModelCode, and submitTaskFormWithStrategy
 * routing behavior with mocked CommandExecutor and TaskService.
 */
@Slf4j
@DisplayName("BPM Form Service - SaveStrategy Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BpmFormServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BpmFormService formService;

    // CommandExecutor is mocked because executing real commands requires
    // full model/command definitions in the database, which is complex to set up.
    @MockitoBean
    private CommandExecutor commandExecutor;


    // ProcessEngineService is mocked because starting real processes requires
    // deployed BPMN definitions in SmartEngine.
    @MockitoBean
    private ProcessEngineService processEngineService;

    @Autowired
    private BpmProcessDefinitionMapper processDefinitionMapper;

    // ==================== buildProcessVariables Tests ====================

    @Test
    @Order(1)
    @DisplayName("STRAT-01: BUSINESS_ONLY strategy does not map businessData to variables")
    void strat01_businessOnlySkipsVariableMapping() {
        FormBindingConfig binding = FormBindingConfig.builder()
                .nodeId("node-01")
                .formRef("cc_contract_edit")
                .variableBindings(Map.of("amount", "contract_amount", "status", "contract_status"))
                .builtinVariables(Map.of("decision", "_decision"))
                .build();

        TaskSubmitRequest request = TaskSubmitRequest.builder()
                .saveStrategy("business_only")
                .businessData(Map.of("amount", 5000, "status", "active"))
                .variables(Map.of("decision", "approve", "comment", "Looks good"))
                .build();

        Map<String, Object> result = formService.buildProcessVariables(request, binding, SaveStrategy.BUSINESS_ONLY);

        // BUSINESS_ONLY should NOT map businessData via variableBindings
        assertThat(result).doesNotContainKey("contract_amount");
        assertThat(result).doesNotContainKey("contract_status");

        // But explicit variables should still be present
        assertThat(result).containsEntry("decision", "approve");
        assertThat(result).containsEntry("comment", "Looks good");

        // Builtin variables should be mapped
        assertThat(result).containsEntry("_decision", "approve");

        log.info("STRAT-01 PASSED: BUSINESS_ONLY skips variable mapping, vars={}", result.keySet());
    }

    @Test
    @Order(2)
    @DisplayName("STRAT-02: DUAL_WRITE strategy maps businessData AND explicit variables")
    void strat02_dualWriteMapsAll() {
        FormBindingConfig binding = FormBindingConfig.builder()
                .nodeId("node-02")
                .formRef("cc_contract_edit")
                .variableBindings(Map.of("amount", "contract_amount", "title", "contract_title"))
                .builtinVariables(Map.of("decision", "_decision", "comment", "_comment"))
                .build();

        TaskSubmitRequest request = TaskSubmitRequest.builder()
                .saveStrategy("dual_write")
                .businessData(Map.of("amount", 9999, "title", "Big Contract"))
                .variables(Map.of("decision", "reject", "comment", "Too expensive"))
                .build();

        Map<String, Object> result = formService.buildProcessVariables(request, binding, SaveStrategy.DUAL_WRITE);

        // variableBindings should map businessData fields
        assertThat(result).containsEntry("contract_amount", 9999);
        assertThat(result).containsEntry("contract_title", "Big Contract");

        // Explicit variables present
        assertThat(result).containsEntry("decision", "reject");
        assertThat(result).containsEntry("comment", "Too expensive");

        // Builtin variables mapped
        assertThat(result).containsEntry("_decision", "reject");
        assertThat(result).containsEntry("_comment", "Too expensive");

        log.info("STRAT-02 PASSED: DUAL_WRITE maps both businessData and variables, keys={}", result.keySet());
    }

    @Test
    @Order(3)
    @DisplayName("STRAT-03: VARIABLE_ONLY strategy maps businessData to variables, no command expected")
    void strat03_variableOnlyMapsToVariables() {
        FormBindingConfig binding = FormBindingConfig.builder()
                .nodeId("node-03")
                .formRef("approval_form")
                .variableBindings(Map.of("verdict", "approval_result"))
                .build();

        TaskSubmitRequest request = TaskSubmitRequest.builder()
                .saveStrategy("variable_only")
                .businessData(Map.of("verdict", "approved"))
                .variables(Map.of("note", "All checks passed"))
                .build();

        Map<String, Object> result = formService.buildProcessVariables(request, binding, SaveStrategy.VARIABLE_ONLY);

        // variableBindings should map
        assertThat(result).containsEntry("approval_result", "approved");

        // Explicit variables present
        assertThat(result).containsEntry("note", "All checks passed");

        log.info("STRAT-03 PASSED: VARIABLE_ONLY maps business data to variables, keys={}", result.keySet());
    }

    @Test
    @Order(4)
    @DisplayName("STRAT-04: Reserved variables are filtered from explicit variables")
    void strat04_reservedVariablesFiltered() {
        TaskSubmitRequest request = TaskSubmitRequest.builder()
                .variables(Map.of(
                        "tenantId", "hacker-tenant",   // reserved
                        "startUserId", "hacker-user",  // reserved
                        "_taskId", "fake-task",         // reserved (starts with _)
                        "decision", "approve"           // allowed
                ))
                .build();

        Map<String, Object> result = formService.buildProcessVariables(request, null, SaveStrategy.VARIABLE_ONLY);

        assertThat(result).doesNotContainKey("tenantId");
        assertThat(result).doesNotContainKey("startUserId");
        assertThat(result).doesNotContainKey("_taskId");
        assertThat(result).containsEntry("decision", "approve");

        log.info("STRAT-04 PASSED: Reserved variables filtered, allowed keys={}", result.keySet());
    }

    @Test
    @Order(5)
    @DisplayName("STRAT-05: Null binding gracefully handles all strategies")
    void strat05_nullBindingGraceful() {
        TaskSubmitRequest request = TaskSubmitRequest.builder()
                .variables(Map.of("action", "approve"))
                .businessData(Map.of("field1", "value1"))
                .build();

        Map<String, Object> result = formService.buildProcessVariables(request, null, SaveStrategy.DUAL_WRITE);

        // Only explicit variables, no variable binding mapping (binding is null)
        assertThat(result).containsEntry("action", "approve");
        assertThat(result).doesNotContainKey("field1"); // not mapped without variableBindings

        log.info("STRAT-05 PASSED: Null binding handled gracefully, keys={}", result.keySet());
    }

    // ==================== deriveModelCode Tests ====================

    @Test
    @Order(10)
    @DisplayName("STRAT-10: deriveModelCode strips known suffixes")
    void strat10_deriveModelCodeStripsSuffixes() {
        assertThat(formService.deriveModelCode("cc_contract_edit")).isEqualTo("cc_contract");
        assertThat(formService.deriveModelCode("cc_contract_form")).isEqualTo("cc_contract");
        assertThat(formService.deriveModelCode("cc_contract_detail")).isEqualTo("cc_contract");
        assertThat(formService.deriveModelCode("cc_contract_create")).isEqualTo("cc_contract");
        assertThat(formService.deriveModelCode("cc_contract_view")).isEqualTo("cc_contract");

        log.info("STRAT-10 PASSED: Known suffixes stripped correctly");
    }

    @Test
    @Order(11)
    @DisplayName("STRAT-11: deriveModelCode returns formRef as-is when no known suffix")
    void strat11_deriveModelCodeNoSuffix() {
        assertThat(formService.deriveModelCode("cc_contract")).isEqualTo("cc_contract");
        assertThat(formService.deriveModelCode("my_custom_page")).isEqualTo("my_custom_page");

        log.info("STRAT-11 PASSED: No suffix returns formRef as-is");
    }

    @Test
    @Order(12)
    @DisplayName("STRAT-12: deriveModelCode handles null/blank")
    void strat12_deriveModelCodeNullBlank() {
        assertThat(formService.deriveModelCode(null)).isNull();
        assertThat(formService.deriveModelCode("")).isNull();
        assertThat(formService.deriveModelCode("   ")).isNull();

        log.info("STRAT-12 PASSED: Null/blank returns null");
    }

    // ==================== submitTaskFormWithStrategy routing Tests ====================

    @Test
    @Order(20)
    @DisplayName("STRAT-20: VARIABLE_ONLY does NOT call CommandExecutor")
    void strat20_variableOnlySkipsCommand() {
        // We need to mock TaskService.completeTask since it's autowired in BpmFormService
        // and we don't have a real task. We'll verify that CommandExecutor is NOT called.
        FormBindingConfig binding = FormBindingConfig.builder()
                .nodeId("node-20")
                .formRef("cc_contract_edit")
                .saveStrategy("variable_only")
                .variableBindings(Map.of("amount", "contract_amount"))
                .build();

        TaskSubmitRequest request = TaskSubmitRequest.builder()
                .saveStrategy("variable_only")
                .businessData(Map.of("amount", 1000))
                .variables(Map.of("decision", "approve"))
                .build();

        // submitTaskFormWithStrategy calls taskService.completeTask which tries to
        // look up the task from SmartEngine. Since we can't easily create a real task,
        // we verify the CommandExecutor was never called by catching the expected exception.
        try {
            formService.submitTaskFormWithStrategy("fake-task-20", request, binding, "biz-key-20");
        } catch (Exception e) {
            // Expected: TaskService.completeTask will fail because the task doesn't exist.
            // But CommandExecutor should NOT have been called.
        }

        verify(commandExecutor, never()).execute(anyString(), any(CommandExecuteRequest.class));
        log.info("STRAT-20 PASSED: VARIABLE_ONLY does not call CommandExecutor");
    }

    @Test
    @Order(21)
    @DisplayName("STRAT-21: BUSINESS_ONLY calls CommandExecutor with correct commandCode")
    void strat21_businessOnlyCallsCommand() {
        when(commandExecutor.execute(anyString(), any(CommandExecuteRequest.class)))
                .thenReturn(CommandExecuteResult.builder()
                        .commandCode("cc_contract.update")
                        .phaseReached("COMPLETED")
                        .executionTimeMs(50)
                        .build());

        FormBindingConfig binding = FormBindingConfig.builder()
                .nodeId("node-21")
                .formRef("cc_contract_edit")
                .saveStrategy("business_only")
                .build();

        TaskSubmitRequest request = TaskSubmitRequest.builder()
                .saveStrategy("business_only")
                .businessData(Map.of("amount", 2000, "title", "Test Contract"))
                .variables(Map.of("decision", "approve"))
                .build();

        try {
            formService.submitTaskFormWithStrategy("fake-task-21", request, binding, "biz-key-21");
        } catch (Exception e) {
            // Expected: task not found in SmartEngine
        }

        // Verify command was called
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<CommandExecuteRequest> reqCaptor = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(commandExecutor, times(1)).execute(codeCaptor.capture(), reqCaptor.capture());

        assertThat(codeCaptor.getValue()).isEqualTo("cc_contract.update");
        assertThat(reqCaptor.getValue().getOperationType()).isEqualTo("UPDATE");
        assertThat(reqCaptor.getValue().getTargetRecordId()).isEqualTo("biz-key-21");
        assertThat(reqCaptor.getValue().getPayload()).containsEntry("amount", 2000);
        assertThat(reqCaptor.getValue().getPayload()).containsEntry("title", "Test Contract");

        log.info("STRAT-21 PASSED: BUSINESS_ONLY calls CommandExecutor with cc_contract.update");
    }

    @Test
    @Order(22)
    @DisplayName("STRAT-22: DUAL_WRITE calls CommandExecutor AND maps variables")
    void strat22_dualWriteCallsCommandAndMapsVars() {
        when(commandExecutor.execute(anyString(), any(CommandExecuteRequest.class)))
                .thenReturn(CommandExecuteResult.builder()
                        .commandCode("cc_contract.update")
                        .phaseReached("COMPLETED")
                        .executionTimeMs(30)
                        .build());

        FormBindingConfig binding = FormBindingConfig.builder()
                .nodeId("node-22")
                .formRef("cc_contract_form")
                .saveStrategy("dual_write")
                .variableBindings(Map.of("amount", "contract_amount"))
                .builtinVariables(Map.of("decision", "_decision"))
                .build();

        TaskSubmitRequest request = TaskSubmitRequest.builder()
                .saveStrategy("dual_write")
                .businessData(Map.of("amount", 3000))
                .variables(Map.of("decision", "approve"))
                .build();

        try {
            formService.submitTaskFormWithStrategy("fake-task-22", request, binding, "biz-key-22");
        } catch (Exception e) {
            // Expected: task not found
        }

        // Command should be called
        verify(commandExecutor, times(1)).execute(eq("cc_contract.update"), any(CommandExecuteRequest.class));

        // Verify the variables that would have been built
        Map<String, Object> vars = formService.buildProcessVariables(request, binding, SaveStrategy.DUAL_WRITE);
        assertThat(vars).containsEntry("contract_amount", 3000);
        assertThat(vars).containsEntry("_decision", "approve");

        log.info("STRAT-22 PASSED: DUAL_WRITE calls command and maps variables");
    }

    @Test
    @Order(23)
    @DisplayName("STRAT-23: Request strategy overrides node default")
    void strat23_requestOverridesNodeDefault() {
        // Node default is BUSINESS_ONLY, but request overrides to VARIABLE_ONLY
        FormBindingConfig binding = FormBindingConfig.builder()
                .nodeId("node-23")
                .formRef("cc_contract_edit")
                .saveStrategy("business_only")  // node default
                .variableBindings(Map.of("amount", "contract_amount"))
                .build();

        TaskSubmitRequest request = TaskSubmitRequest.builder()
                .saveStrategy("variable_only")  // override
                .businessData(Map.of("amount", 4000))
                .variables(Map.of())
                .build();

        try {
            formService.submitTaskFormWithStrategy("fake-task-23", request, binding, "biz-key-23");
        } catch (Exception e) {
            // Expected: task not found
        }

        // VARIABLE_ONLY should NOT call command
        verify(commandExecutor, never()).execute(anyString(), any(CommandExecuteRequest.class));

        // But should map variables
        Map<String, Object> vars = formService.buildProcessVariables(request, binding, SaveStrategy.VARIABLE_ONLY);
        assertThat(vars).containsEntry("contract_amount", 4000);

        log.info("STRAT-23 PASSED: Request strategy overrides node default");
    }

    @Test
    @Order(24)
    @DisplayName("STRAT-24: Empty businessData skips Command execution gracefully")
    void strat24_emptyBusinessDataSkipsCommand() {
        FormBindingConfig binding = FormBindingConfig.builder()
                .nodeId("node-24")
                .formRef("cc_contract_edit")
                .saveStrategy("business_only")
                .build();

        TaskSubmitRequest request = TaskSubmitRequest.builder()
                .saveStrategy("business_only")
                .businessData(Map.of())  // empty
                .variables(Map.of("decision", "approve"))
                .build();

        try {
            formService.submitTaskFormWithStrategy("fake-task-24", request, binding, "biz-key-24");
        } catch (Exception e) {
            // Expected: task not found
        }

        // Empty businessData should skip command
        verify(commandExecutor, never()).execute(anyString(), any(CommandExecuteRequest.class));

        log.info("STRAT-24 PASSED: Empty businessData skips Command execution");
    }

    @Test
    @Order(25)
    @DisplayName("STRAT-25: Command failure propagates as IllegalStateException")
    void strat25_commandFailurePropagates() {
        when(commandExecutor.execute(anyString(), any(CommandExecuteRequest.class)))
                .thenThrow(new RuntimeException("DB connection failed"));

        FormBindingConfig binding = FormBindingConfig.builder()
                .nodeId("node-25")
                .formRef("cc_contract_edit")
                .saveStrategy("business_only")
                .build();

        TaskSubmitRequest request = TaskSubmitRequest.builder()
                .saveStrategy("business_only")
                .businessData(Map.of("amount", 5000))
                .variables(Map.of())
                .build();

        assertThatThrownBy(() ->
                formService.submitTaskFormWithStrategy("fake-task-25", request, binding, "biz-key-25"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Business data write failed");

        log.info("STRAT-25 PASSED: Command failure propagates as IllegalStateException");
    }

    // ==================== startProcessWithForm Tests ====================

    @Test
    @Order(30)
    @DisplayName("START-30: BUSINESS_ONLY strategy creates record and starts process")
    void start30_businessOnlyCreatesRecordAndStartsProcess() {
        // Setup: insert a process definition so findByProcessKey returns non-null
        long ts = System.currentTimeMillis();
        String processKey = "test_process_" + ts;
        BpmProcessDefinition def = new BpmProcessDefinition();
        def.setPid(UniqueIdGenerator.generate());
        def.setProcessKey(processKey);
        def.setProcessName("Test Process " + ts);
        def.setBpmnContent("<definitions/>");
        def.setIsCurrent(true);
        def.setDeletedFlag(false);
        processDefinitionMapper.insert(def);

        // Mock CommandExecutor to return a result with recordId
        when(commandExecutor.execute(eq("cc_contract.create"), any(CommandExecuteRequest.class)))
                .thenReturn(CommandExecuteResult.builder()
                        .commandCode("cc_contract.create")
                        .phaseReached("COMPLETED")
                        .data(Map.of("id", "record-123"))
                        .executionTimeMs(50)
                        .build());

        // Mock ProcessEngineService
        ProcessInstance mockInstance = mock(ProcessInstance.class);
        when(mockInstance.getInstanceId()).thenReturn("pi-001");
        when(processEngineService.startProcess(eq(processKey), eq("record-123"), any()))
                .thenReturn(mockInstance);

        ProcessStartRequest request = ProcessStartRequest.builder()
                .modelCode("cc_contract")
                .businessData(Map.of("title", "Test Contract", "amount", 5000))
                .variables(Map.of("urgency", "high"))
                .saveStrategy("business_only")
                .build();

        Map<String, Object> result = formService.startProcessWithForm(processKey, request);

        // Verify result
        assertThat(result).containsEntry("processInstanceId", "pi-001");
        assertThat(result).containsEntry("businessKey", "record-123");

        // Verify command was called for record creation
        ArgumentCaptor<CommandExecuteRequest> cmdCaptor = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(commandExecutor, times(1)).execute(eq("cc_contract.create"), cmdCaptor.capture());
        assertThat(cmdCaptor.getValue().getOperationType()).isEqualTo("CREATE");
        assertThat(cmdCaptor.getValue().getPayload()).containsEntry("title", "Test Contract");
        assertThat(cmdCaptor.getValue().getPayload()).containsEntry("amount", 5000);

        // Verify process was started with correct params
        verify(processEngineService, times(1)).startProcess(eq(processKey), eq("record-123"), any());

        log.info("START-30 PASSED: BUSINESS_ONLY creates record and starts process");
    }

    @Test
    @Order(31)
    @DisplayName("START-31: VARIABLE_ONLY strategy skips record creation")
    void start31_variableOnlySkipsRecordCreation() {
        long ts = System.currentTimeMillis();
        String processKey = "test_process_vo_" + ts;
        BpmProcessDefinition def = new BpmProcessDefinition();
        def.setPid(UniqueIdGenerator.generate());
        def.setProcessKey(processKey);
        def.setProcessName("Test Process VO " + ts);
        def.setBpmnContent("<definitions/>");
        def.setIsCurrent(true);
        def.setDeletedFlag(false);
        processDefinitionMapper.insert(def);

        // Mock ProcessEngineService — businessKey should be null
        ProcessInstance mockInstance = mock(ProcessInstance.class);
        when(mockInstance.getInstanceId()).thenReturn("pi-002");
        when(processEngineService.startProcess(eq(processKey), isNull(), any()))
                .thenReturn(mockInstance);

        ProcessStartRequest request = ProcessStartRequest.builder()
                .modelCode("cc_contract")
                .businessData(Map.of("title", "Should Be Ignored"))
                .variables(Map.of("decision", "approve"))
                .saveStrategy("variable_only")
                .build();

        Map<String, Object> result = formService.startProcessWithForm(processKey, request);

        // Verify result
        assertThat(result).containsEntry("processInstanceId", "pi-002");
        assertThat(result.get("businessKey")).isNull();

        // CommandExecutor should NOT have been called
        verify(commandExecutor, never()).execute(anyString(), any(CommandExecuteRequest.class));

        // Process should be started with null businessKey
        verify(processEngineService, times(1)).startProcess(eq(processKey), isNull(), any());

        log.info("START-31 PASSED: VARIABLE_ONLY skips record creation");
    }

    @Test
    @Order(32)
    @DisplayName("START-32: Non-existent processKey throws IllegalArgumentException")
    void start32_nonExistentProcessKeyThrows() {
        // Use variable_only so no Command execution is attempted before processKey check
        ProcessStartRequest request = ProcessStartRequest.builder()
                .variables(Map.of("decision", "approve"))
                .saveStrategy("variable_only")
                .build();

        assertThatThrownBy(() ->
                formService.startProcessWithForm("nonexistent_process_key_" + System.currentTimeMillis(), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Process definition not found");

        log.info("START-32 PASSED: Non-existent processKey throws IllegalArgumentException");
    }

    @Test
    @Order(33)
    @DisplayName("START-33: Command failure during record creation rolls back")
    void start33_commandFailureRollsBack() {
        long ts = System.currentTimeMillis();
        String processKey = "test_process_fail_" + ts;
        BpmProcessDefinition def = new BpmProcessDefinition();
        def.setPid(UniqueIdGenerator.generate());
        def.setProcessKey(processKey);
        def.setProcessName("Test Process Fail " + ts);
        def.setBpmnContent("<definitions/>");
        def.setIsCurrent(true);
        def.setDeletedFlag(false);
        processDefinitionMapper.insert(def);

        when(commandExecutor.execute(anyString(), any(CommandExecuteRequest.class)))
                .thenThrow(new RuntimeException("Validation failed: required field missing"));

        ProcessStartRequest request = ProcessStartRequest.builder()
                .modelCode("cc_contract")
                .businessData(Map.of("amount", 1000))
                .saveStrategy("business_only")
                .build();

        assertThatThrownBy(() ->
                formService.startProcessWithForm(processKey, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Business record creation failed");

        // Process should NOT have been started
        verify(processEngineService, never()).startProcess(anyString(), anyString(), any());

        log.info("START-33 PASSED: Command failure prevents process start");
    }

    // ==================== getTaskActionsForNode Tests ====================

    @Test
    @Order(40)
    @DisplayName("TA-40: returns declared approve/reject taskActions from designerJson")
    void ta40_returnsDeclaredTaskActions() {
        long ts = System.currentTimeMillis();
        String processKey = "ta_process_" + ts;
        BpmProcessDefinition def = new BpmProcessDefinition();
        def.setPid(UniqueIdGenerator.generate());
        def.setProcessKey(processKey);
        def.setProcessName("TA Process " + ts);
        def.setBpmnContent("<definitions/>");
        def.setIsCurrent(true);
        def.setDeletedFlag(false);
        // Column default is '[]'::jsonb; PluginSettingsTypeHandler expects an
        // object — explicitly initialise to an empty map so select round-trips.
        def.setBusinessDataBindings(new HashMap<>());
        def.setFormBindings(new HashMap<>());
        Map<String, Object> designer = new HashMap<>();
        designer.put("nodes", List.of(
                Map.of(
                        "id", "task_manager_approve",
                        "type", "userTask",
                        "data", Map.of(
                                "taskActions", List.of(
                                        Map.of(
                                                "key", "approve",
                                                "type", "complete",
                                                "resultVariable", "taskResult",
                                                "resultValue", "approved"
                                        ),
                                        Map.of(
                                                "key", "reject",
                                                "type", "complete",
                                                "resultVariable", "taskResult",
                                                "resultValue", "rejected",
                                                "requireComment", true
                                        )
                                )
                        )
                )
        ));
        def.setExtension(Map.of("designerJson", designer));
        processDefinitionMapper.insert(def);

        List<TaskActionDef> actions = formService.getTaskActionsForNode(
                processKey, "task_manager_approve");

        assertThat(actions).isNotNull().hasSize(2);
        assertThat(actions.get(0).getKey()).isEqualTo("approve");
        assertThat(actions.get(0).getType()).isEqualTo("complete");
        assertThat(actions.get(0).getResultVariable()).isEqualTo("taskResult");
        assertThat(actions.get(0).getResultValue()).isEqualTo("approved");
        assertThat(actions.get(1).getKey()).isEqualTo("reject");
        assertThat(actions.get(1).getResultValue()).isEqualTo("rejected");
        assertThat(actions.get(1).getRequireComment()).isTrue();

        log.info("TA-40 PASSED: taskActions parsed from designerJson");
    }

    @Test
    @Order(41)
    @DisplayName("TA-41: returns null when process has no designerJson")
    void ta41_noDesignerJsonReturnsNull() {
        long ts = System.currentTimeMillis();
        String processKey = "ta_no_designer_" + ts;
        BpmProcessDefinition def = new BpmProcessDefinition();
        def.setPid(UniqueIdGenerator.generate());
        def.setProcessKey(processKey);
        def.setProcessName("TA No Designer " + ts);
        def.setBpmnContent("<definitions/>");
        def.setIsCurrent(true);
        def.setDeletedFlag(false);
        // Column default is '[]'::jsonb; PluginSettingsTypeHandler expects an
        // object — explicitly initialise to an empty map so select round-trips.
        def.setBusinessDataBindings(new HashMap<>());
        def.setFormBindings(new HashMap<>());
        processDefinitionMapper.insert(def);

        assertThat(formService.getTaskActionsForNode(processKey, "any-node")).isNull();

        log.info("TA-41 PASSED: null returned when designerJson absent");
    }

    @Test
    @Order(42)
    @DisplayName("TA-42: returns null when node id does not match any designerJson node")
    void ta42_unknownNodeReturnsNull() {
        long ts = System.currentTimeMillis();
        String processKey = "ta_unknown_node_" + ts;
        BpmProcessDefinition def = new BpmProcessDefinition();
        def.setPid(UniqueIdGenerator.generate());
        def.setProcessKey(processKey);
        def.setProcessName("TA Unknown Node " + ts);
        def.setBpmnContent("<definitions/>");
        def.setIsCurrent(true);
        def.setDeletedFlag(false);
        // Column default is '[]'::jsonb; PluginSettingsTypeHandler expects an
        // object — explicitly initialise to an empty map so select round-trips.
        def.setBusinessDataBindings(new HashMap<>());
        def.setFormBindings(new HashMap<>());
        Map<String, Object> designer = Map.of(
                "nodes", List.of(
                        Map.of(
                                "id", "task_manager_approve",
                                "type", "userTask",
                                "data", Map.of(
                                        "taskActions", List.of(
                                                Map.of(
                                                        "key", "approve",
                                                        "type", "complete",
                                                        "resultVariable", "taskResult",
                                                        "resultValue", "approved"
                                                )
                                        )
                                )
                        )
                )
        );
        def.setExtension(Map.of("designerJson", designer));
        processDefinitionMapper.insert(def);

        assertThat(formService.getTaskActionsForNode(processKey, "nonexistent_node"))
                .isNull();

        log.info("TA-42 PASSED: unknown nodeId returns null");
    }

    @Test
    @Order(43)
    @DisplayName("TA-43: returns null when node has no taskActions array")
    void ta43_nodeWithoutTaskActionsReturnsNull() {
        long ts = System.currentTimeMillis();
        String processKey = "ta_no_actions_" + ts;
        BpmProcessDefinition def = new BpmProcessDefinition();
        def.setPid(UniqueIdGenerator.generate());
        def.setProcessKey(processKey);
        def.setProcessName("TA No Actions " + ts);
        def.setBpmnContent("<definitions/>");
        def.setIsCurrent(true);
        def.setDeletedFlag(false);
        // Column default is '[]'::jsonb; PluginSettingsTypeHandler expects an
        // object — explicitly initialise to an empty map so select round-trips.
        def.setBusinessDataBindings(new HashMap<>());
        def.setFormBindings(new HashMap<>());
        Map<String, Object> designer = Map.of(
                "nodes", List.of(
                        Map.of(
                                "id", "plain_userTask",
                                "type", "userTask",
                                "data", Map.of("label", "审批")
                        )
                )
        );
        def.setExtension(Map.of("designerJson", designer));
        processDefinitionMapper.insert(def);

        assertThat(formService.getTaskActionsForNode(processKey, "plain_userTask"))
                .isNull();

        log.info("TA-43 PASSED: node without taskActions returns null");
    }
}
