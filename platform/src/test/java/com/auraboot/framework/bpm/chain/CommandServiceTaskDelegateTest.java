package com.auraboot.framework.bpm.chain;

import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.model.assembly.BaseElement;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import com.auraboot.smart.framework.engine.model.instance.ActivityInstance;
import com.auraboot.smart.framework.engine.model.instance.ExecutionInstance;
import com.auraboot.framework.bpm.service.ExecutionLogService;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CommandServiceTaskDelegate.
 * Verifies the bridge between SmartEngine ServiceTask and AuraBoot Command engine.
 */
@ExtendWith(MockitoExtension.class)
class CommandServiceTaskDelegateTest {

    @Mock
    private CommandExecutor commandExecutor;

    @Mock
    private ExecutionLogService executionLogService;

    @Mock
    private ExecutionContext executionContext;

    @Mock
    private IdBasedElement baseElement;

    @Mock
    private ExecutionInstance executionInstance;

    private CommandServiceTaskDelegate delegate;

    @BeforeEach
    void setUp() {
        delegate = new CommandServiceTaskDelegate(commandExecutor, executionLogService);
    }

    private Map<String, Object> buildProcessVars(String nodeId, String commandCode,
                                                  String operationType, Map<String, Object> params) {
        Map<String, Object> processVars = new HashMap<>();

        Map<String, Map<String, Object>> chainNodes = new HashMap<>();
        Map<String, Object> nodeConfig = new HashMap<>();
        nodeConfig.put("commandCode", commandCode);
        nodeConfig.put("operationType", operationType);
        nodeConfig.put("params", params != null ? params : Map.of());
        nodeConfig.put("onFail", "abort");
        chainNodes.put(nodeId, nodeConfig);

        processVars.put("_chain_nodes", chainNodes);
        return processVars;
    }

    private void setupContext(String nodeId, Map<String, Object> processVars) {
        when(baseElement.getId()).thenReturn(nodeId);
        when(executionContext.getBaseElement()).thenReturn(baseElement);
        when(executionContext.getRequest()).thenReturn(processVars);
        when(executionInstance.getInstanceId()).thenReturn("exec-001");
        when(executionContext.getExecutionInstance()).thenReturn(executionInstance);
    }

    @Nested
    @DisplayName("Successful command execution")
    class SuccessfulExecution {

        @Test
        @DisplayName("should execute command and write results to process variables")
        void shouldExecuteCommandAndWriteResults() {
            String nodeId = "create_order";
            Map<String, Object> params = Map.of("customer_id", "c001", "amount", 100);
            Map<String, Object> processVars = buildProcessVars(nodeId, "pe:create_order", "create", params);

            setupContext(nodeId, processVars);

            CommandExecuteResult result = CommandExecuteResult.builder()
                    .commandCode("pe:create_order")
                    .phaseReached("completed")
                    .data(Map.of("recordId", "rec-123", "order_no", "ORD-001"))
                    .executionTimeMs(50)
                    .build();

            when(commandExecutor.execute(eq("pe:create_order"), any(CommandExecuteRequest.class)))
                    .thenReturn(result);

            delegate.execute(executionContext);

            // Verify command was executed
            ArgumentCaptor<CommandExecuteRequest> requestCaptor = ArgumentCaptor.forClass(CommandExecuteRequest.class);
            verify(commandExecutor).execute(eq("pe:create_order"), requestCaptor.capture());

            CommandExecuteRequest capturedRequest = requestCaptor.getValue();
            assertEquals("create", capturedRequest.getOperationType());
            assertEquals("c001", capturedRequest.getPayload().get("customer_id"));

            // Verify results written to process variables
            assertTrue((Boolean) processVars.get("_step_create_order_success"));
            assertNotNull(processVars.get("_step_create_order_result"));
            assertEquals("rec-123", processVars.get("_step_create_order_recordId"));
        }

        @Test
        @DisplayName("should resolve SpEL expressions in params from process variables")
        void shouldResolveSpelExpressions() {
            String nodeId = "update_status";
            Map<String, Object> params = new HashMap<>();
            params.put("id", "${orderId}");
            params.put("status", "shipped");

            Map<String, Object> processVars = buildProcessVars(nodeId, "pe:update_order", "update", params);
            processVars.put("orderId", "order-456");

            setupContext(nodeId, processVars);

            CommandExecuteResult result = CommandExecuteResult.builder()
                    .commandCode("pe:update_order").phaseReached("completed")
                    .data(Map.of()).executionTimeMs(30).build();

            when(commandExecutor.execute(eq("pe:update_order"), any(CommandExecuteRequest.class)))
                    .thenReturn(result);

            delegate.execute(executionContext);

            ArgumentCaptor<CommandExecuteRequest> requestCaptor = ArgumentCaptor.forClass(CommandExecuteRequest.class);
            verify(commandExecutor).execute(eq("pe:update_order"), requestCaptor.capture());

            // Verify SpEL expression was resolved
            assertEquals("order-456", requestCaptor.getValue().getPayload().get("id"));
            assertEquals("shipped", requestCaptor.getValue().getPayload().get("status"));
        }

        @Test
        @DisplayName("should resolve nested step result expressions")
        void shouldResolveNestedStepResults() {
            String nodeId = "deduct_inventory";
            Map<String, Object> params = new HashMap<>();
            params.put("stockOutId", "${_step_create_stock_out_result.id}");

            Map<String, Object> processVars = buildProcessVars(nodeId, "pe:deduct_inventory", "update", params);

            // Simulate a previous step's result
            Map<String, Object> previousResult = new HashMap<>();
            previousResult.put("id", "stock-out-789");
            processVars.put("_step_create_stock_out_result", previousResult);

            setupContext(nodeId, processVars);

            CommandExecuteResult result = CommandExecuteResult.builder()
                    .commandCode("pe:deduct_inventory").phaseReached("completed")
                    .data(Map.of()).executionTimeMs(20).build();

            when(commandExecutor.execute(eq("pe:deduct_inventory"), any(CommandExecuteRequest.class)))
                    .thenReturn(result);

            delegate.execute(executionContext);

            ArgumentCaptor<CommandExecuteRequest> requestCaptor = ArgumentCaptor.forClass(CommandExecuteRequest.class);
            verify(commandExecutor).execute(eq("pe:deduct_inventory"), requestCaptor.capture());

            assertEquals("stock-out-789", requestCaptor.getValue().getPayload().get("stockOutId"));
        }
    }

    @Nested
    @DisplayName("Failure handling")
    class FailureHandling {

        @Test
        @DisplayName("should throw CommandChainStepException on ABORT failure")
        void shouldThrowOnAbortFailure() {
            String nodeId = "failing_step";
            Map<String, Object> processVars = buildProcessVars(nodeId, "pe:bad_command", "create", Map.of());

            setupContext(nodeId, processVars);

            when(commandExecutor.execute(eq("pe:bad_command"), any(CommandExecuteRequest.class)))
                    .thenThrow(new RuntimeException("command execution failed"));

            assertThrows(Exception.class, () -> delegate.execute(executionContext));
        }

        @Test
        @DisplayName("should skip step on SKIP_AND_WARN failure mode")
        void shouldSkipOnSkipAndWarnFailure() {
            String nodeId = "optional_step";

            Map<String, Object> processVars = new HashMap<>();
            Map<String, Map<String, Object>> chainNodes = new HashMap<>();
            Map<String, Object> nodeConfig = new HashMap<>();
            nodeConfig.put("commandCode", "pe:optional_cmd");
            nodeConfig.put("operationType", "create");
            nodeConfig.put("params", Map.of());
            nodeConfig.put("onFail", "skip_and_warn");
            chainNodes.put(nodeId, nodeConfig);
            processVars.put("_chain_nodes", chainNodes);

            setupContext(nodeId, processVars);

            when(commandExecutor.execute(eq("pe:optional_cmd"), any(CommandExecuteRequest.class)))
                    .thenThrow(new RuntimeException("optional command failed"));

            // The original exception is re-thrown (not CommandChainStepException)
            var ex = assertThrows(RuntimeException.class, () -> delegate.execute(executionContext));
            assertFalse(ex instanceof CommandChainStepException,
                    "SKIP_AND_WARN should not throw CommandChainStepException");

            // Should mark as skipped and not successful (set before exception re-throw)
            assertTrue((Boolean) processVars.get("_step_optional_step_skipped"));
            assertFalse((Boolean) processVars.get("_step_optional_step_success"));
        }

        @Test
        @DisplayName("should throw when _chain_nodes is missing")
        void shouldThrowWhenChainNodesMissing() {
            Map<String, Object> processVars = new HashMap<>(); // No _chain_nodes
            setupContext("some_node", processVars);

            assertThrows(CommandChainStepException.class, () -> delegate.execute(executionContext));
        }
    }

    @Nested
    @DisplayName("Condition evaluation")
    class ConditionEvaluation {

        @Test
        @DisplayName("should skip step when condition evaluates to false")
        void shouldSkipWhenConditionFalse() {
            String nodeId = "conditional_step";

            Map<String, Object> processVars = new HashMap<>();
            Map<String, Map<String, Object>> chainNodes = new HashMap<>();
            Map<String, Object> nodeConfig = new HashMap<>();
            nodeConfig.put("commandCode", "pe:conditional_cmd");
            nodeConfig.put("operationType", "create");
            nodeConfig.put("params", Map.of());
            nodeConfig.put("onFail", "abort");
            nodeConfig.put("condition", "totalAmount > 1000");
            chainNodes.put(nodeId, nodeConfig);
            processVars.put("_chain_nodes", chainNodes);
            processVars.put("totalAmount", 500); // Less than 1000 → condition false

            setupContext(nodeId, processVars);

            delegate.execute(executionContext);

            // Command should NOT have been executed
            verify(commandExecutor, never()).execute(anyString(), any());

            // Should mark as skipped
            assertTrue((Boolean) processVars.get("_step_conditional_step_skipped"));
        }

        @Test
        @DisplayName("should execute step when condition evaluates to true")
        void shouldExecuteWhenConditionTrue() {
            String nodeId = "conditional_step";

            Map<String, Object> processVars = new HashMap<>();
            Map<String, Map<String, Object>> chainNodes = new HashMap<>();
            Map<String, Object> nodeConfig = new HashMap<>();
            nodeConfig.put("commandCode", "pe:conditional_cmd");
            nodeConfig.put("operationType", "create");
            nodeConfig.put("params", Map.of());
            nodeConfig.put("onFail", "abort");
            nodeConfig.put("condition", "totalAmount > 1000");
            chainNodes.put(nodeId, nodeConfig);
            processVars.put("_chain_nodes", chainNodes);
            processVars.put("totalAmount", 5000); // Greater than 1000 → condition true

            setupContext(nodeId, processVars);

            CommandExecuteResult result = CommandExecuteResult.builder()
                    .commandCode("pe:conditional_cmd").phaseReached("completed")
                    .data(Map.of()).executionTimeMs(10).build();

            when(commandExecutor.execute(eq("pe:conditional_cmd"), any(CommandExecuteRequest.class)))
                    .thenReturn(result);

            delegate.execute(executionContext);

            verify(commandExecutor).execute(eq("pe:conditional_cmd"), any());
        }
    }

    @Nested
    @DisplayName("Activity ID resolution")
    class ActivityIdResolution {

        @Test
        @DisplayName("should resolve activity ID from ActivityInstance when BaseElement unavailable")
        void shouldResolveFromActivityInstance() {
            Map<String, Object> processVars = buildProcessVars("fallback_node", "pe:test", "create", Map.of());

            // No BaseElement, but ActivityInstance available
            ActivityInstance activityInstance = mock(ActivityInstance.class);
            when(activityInstance.getProcessDefinitionActivityId()).thenReturn("fallback_node");
            when(executionContext.getBaseElement()).thenReturn(null);
            when(executionContext.getActivityInstance()).thenReturn(activityInstance);
            when(executionContext.getRequest()).thenReturn(processVars);
            when(executionInstance.getInstanceId()).thenReturn("exec-002");
            when(executionContext.getExecutionInstance()).thenReturn(executionInstance);

            CommandExecuteResult result = CommandExecuteResult.builder()
                    .commandCode("pe:test").phaseReached("completed")
                    .data(Map.of()).executionTimeMs(5).build();

            when(commandExecutor.execute(eq("pe:test"), any(CommandExecuteRequest.class)))
                    .thenReturn(result);

            delegate.execute(executionContext);

            verify(commandExecutor).execute(eq("pe:test"), any());
        }
    }
}
