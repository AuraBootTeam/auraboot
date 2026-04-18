package com.auraboot.framework.bpm.service;

import com.auraboot.framework.bpm.entity.BpmNodeHook;
import com.auraboot.framework.bpm.mapper.BpmNodeHookMapper;
import com.auraboot.framework.bpm.service.BpmNodeHookService.HookExecutionResult;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@link BpmNodeHookService} vocabulary aliasing between
 * frontend (UI) vocab and backend (internal) vocab (GAP-255 / GAP-256).
 *
 * <p>Covers:
 * <ul>
 *   <li>hookType aliases: {@code pre_execute / post_execute / pre_complete /
 *       post_complete} normalize to {@code pre_check / post_action}.</li>
 *   <li>actionType aliases: {@code http_callback} normalizes to {@code rest_call}.</li>
 *   <li>New {@code command} actionType invokes {@link CommandExecutor} with the
 *       correct payload/operationType/targetRecordId.</li>
 *   <li>Backward compat: rows written directly with backend vocab continue to
 *       resolve and dispatch.</li>
 * </ul>
 *
 * <p>{@link CommandExecutor} is mocked to assert dispatch parameters deterministically
 * without depending on a deployed test model.
 */
@Slf4j
@DisplayName("BPM Node Hook Service — Vocab Alias Tests (GAP-255 / GAP-256)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BpmNodeHookServiceVocabAliasIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BpmNodeHookService hookService;

    @Autowired
    private BpmNodeHookMapper hookMapper;

    @MockitoBean
    private CommandExecutor commandExecutor;

    private BpmNodeHook buildHook(String processKey, String nodeId, String hookType,
                                  Map<String, Object> config) {
        return BpmNodeHook.builder()
                .processKey(processKey)
                .nodeId(nodeId)
                .hookType(hookType)
                .hookConfig(config)
                .failStrategy("block")
                .async(false)
                .enabled(true)
                .executionOrder(1)
                .build();
    }

    // ==================== hookType aliasing (GAP-255) ====================

    @Test
    @Order(1)
    @DisplayName("VOCAB-01: hookType=pre_execute normalizes to pre_check in DB and dispatches as PRE_CHECK")
    void vocab01_preExecuteNormalizesToPreCheck() {
        String processKey = "vocab-pre-execute-" + System.nanoTime();
        String nodeId = "n1";

        // UI emits pre_execute; service should persist as pre_check
        BpmNodeHook created = hookService.createHook(buildHook(
                processKey, nodeId, "pre_execute",
                Map.of("type", "script", "script", "true")
        ));

        BpmNodeHook fromDb = hookMapper.findByPid(created.getPid());
        assertThat(fromDb.getHookType())
                .as("DB row must store backend vocab 'pre_check', not UI vocab")
                .isEqualTo("pre_check");

        // Query with UI vocab also works (normalizeHookType in getHooks)
        List<BpmNodeHook> byUiVocab = hookService.getHooks(processKey, nodeId, "pre_execute");
        assertThat(byUiVocab).hasSize(1);

        // Executing pre-checks dispatches this row
        HookExecutionResult result = hookService.executePreChecks(processKey, nodeId, Map.of());
        assertThat(result.passed()).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("VOCAB-02: hookType=post_execute / post_complete normalize to post_action")
    void vocab02_postExecuteNormalizesToPostAction() {
        String processKey = "vocab-post-execute-" + System.nanoTime();
        String nodeId = "n2";

        hookService.createHook(buildHook(
                processKey, nodeId, "post_execute",
                Map.of("type", "script", "script", "true")
        ));
        hookService.createHook(buildHook(
                processKey, nodeId, "post_complete",
                Map.of("type", "script", "script", "true")
        ));

        List<BpmNodeHook> postActions = hookService.getHooks(processKey, nodeId, "post_action");
        assertThat(postActions)
                .as("Both post_execute and post_complete must land as post_action")
                .hasSize(2)
                .allSatisfy(h -> assertThat(h.getHookType()).isEqualTo("post_action"));

        assertDoesNotThrow(() -> hookService.executePostActions(processKey, nodeId, Map.of()));
    }

    @Test
    @Order(3)
    @DisplayName("VOCAB-03: hookType=pre_complete normalizes to pre_check")
    void vocab03_preCompleteNormalizesToPreCheck() {
        String processKey = "vocab-pre-complete-" + System.nanoTime();
        String nodeId = "n3";

        BpmNodeHook created = hookService.createHook(buildHook(
                processKey, nodeId, "pre_complete",
                Map.of("type", "script", "script", "true")
        ));

        BpmNodeHook fromDb = hookMapper.findByPid(created.getPid());
        assertThat(fromDb.getHookType()).isEqualTo("pre_check");
    }

    @Test
    @Order(4)
    @DisplayName("VOCAB-04: backward compat — hookType=pre_check / post_action still work")
    void vocab04_backwardCompatBackendVocab() {
        String processKey = "vocab-backcompat-" + System.nanoTime();
        String nodeId = "n4";

        hookService.createHook(buildHook(
                processKey, nodeId, "pre_check",
                Map.of("type", "script", "script", "true")
        ));
        hookService.createHook(buildHook(
                processKey, nodeId, "post_action",
                Map.of("type", "script", "script", "true")
        ));

        assertThat(hookService.getHooks(processKey, nodeId, "pre_check")).hasSize(1);
        assertThat(hookService.getHooks(processKey, nodeId, "post_action")).hasSize(1);
    }

    // ==================== actionType aliasing (GAP-256) ====================

    @Test
    @Order(5)
    @DisplayName("VOCAB-05: actionType=http_callback dispatches through rest_call path (fail → BLOCK)")
    void vocab05_httpCallbackRoutesToRestCall() {
        String processKey = "vocab-http-callback-" + System.nanoTime();
        String nodeId = "n5";

        // Use a non-routable host; rest_call catches HTTP exception and returns false
        hookService.createHook(buildHook(
                processKey, nodeId, "pre_execute",
                Map.of("type", "http_callback",
                        "url", "http://192.0.2.1:9999/unreachable",
                        "method", "post")
        ));

        HookExecutionResult result = hookService.executePreChecks(processKey, nodeId, Map.of());

        assertThat(result.passed())
                .as("http_callback alias must dispatch to rest_call; unreachable URL => BLOCK => passed=false")
                .isFalse();
        assertThat(result.message()).isNotNull();
    }

    @Test
    @Order(6)
    @DisplayName("VOCAB-06: actionType=rest_call (backend vocab) still works")
    void vocab06_restCallBackwardCompat() {
        String processKey = "vocab-rest-call-" + System.nanoTime();
        String nodeId = "n6";

        hookService.createHook(buildHook(
                processKey, nodeId, "pre_check",
                Map.of("type", "rest_call",
                        "url", "http://192.0.2.1:9999/unreachable",
                        "method", "post")
        ));

        HookExecutionResult result = hookService.executePreChecks(processKey, nodeId, Map.of());
        assertThat(result.passed()).isFalse();
    }

    // ==================== command actionType (GAP-256 new) ====================

    @Test
    @Order(7)
    @DisplayName("VOCAB-07: actionType=command invokes CommandExecutor with payload/operationType/targetRecordId")
    void vocab07_commandActionInvokesExecutor() {
        reset(commandExecutor);
        when(commandExecutor.execute(any(String.class), any(CommandExecuteRequest.class)))
                .thenReturn(CommandExecuteResult.builder().phaseReached("COMPLETE").build());

        String processKey = "vocab-command-" + System.nanoTime();
        String nodeId = "n7";
        String commandCode = "wd:create_leave_balance";

        hookService.createHook(buildHook(
                processKey, nodeId, "post_execute",
                Map.of(
                        "type", "command",
                        "commandCode", commandCode,
                        "operationType", "create",
                        "targetRecordId", "rec-123",
                        "payload", Map.of("userId", "u-1", "amount", 5)
                )
        ));

        hookService.executePostActions(processKey, nodeId, Map.of("processVar", "pv"));

        ArgumentCaptor<CommandExecuteRequest> reqCaptor = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(commandExecutor, times(1)).execute(eq(commandCode), reqCaptor.capture());

        CommandExecuteRequest req = reqCaptor.getValue();
        assertThat(req.getOperationType()).isEqualTo("create");
        assertThat(req.getTargetRecordId()).isEqualTo("rec-123");
        assertThat(req.getPayload())
                .as("When config.payload is present, it takes precedence over process variables")
                .containsEntry("userId", "u-1")
                .containsEntry("amount", 5);
    }

    @Test
    @Order(8)
    @DisplayName("VOCAB-08: command action with no explicit payload falls back to hook variables")
    void vocab08_commandActionDefaultsPayloadToVariables() {
        reset(commandExecutor);
        when(commandExecutor.execute(any(String.class), any(CommandExecuteRequest.class)))
                .thenReturn(CommandExecuteResult.builder().phaseReached("COMPLETE").build());

        String processKey = "vocab-command-default-" + System.nanoTime();
        String nodeId = "n8";

        hookService.createHook(buildHook(
                processKey, nodeId, "post_complete",
                Map.of(
                        "type", "command",
                        "commandCode", "wd:update_leave_balance"
                        // no payload / operationType / targetRecordId
                )
        ));

        Map<String, Object> processVars = Map.of("requestId", "r-42", "status", "approved");
        hookService.executePostActions(processKey, nodeId, processVars);

        ArgumentCaptor<CommandExecuteRequest> reqCaptor = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(commandExecutor, times(1)).execute(eq("wd:update_leave_balance"), reqCaptor.capture());

        CommandExecuteRequest req = reqCaptor.getValue();
        assertThat(req.getOperationType()).isNull();
        assertThat(req.getTargetRecordId()).isNull();
        assertThat(req.getPayload())
                .as("Without explicit payload, hook variables are passed through")
                .containsEntry("requestId", "r-42")
                .containsEntry("status", "approved");
    }

    @Test
    @Order(9)
    @DisplayName("VOCAB-09: normalize helpers return internal vocab for UI inputs and pass-through for unknown")
    void vocab09_normalizeHelpersDirect() {
        // hookType
        assertThat(BpmNodeHookService.normalizeHookType("pre_execute")).isEqualTo("pre_check");
        assertThat(BpmNodeHookService.normalizeHookType("PRE_EXECUTE")).isEqualTo("pre_check");
        assertThat(BpmNodeHookService.normalizeHookType("pre_complete")).isEqualTo("pre_check");
        assertThat(BpmNodeHookService.normalizeHookType("post_execute")).isEqualTo("post_action");
        assertThat(BpmNodeHookService.normalizeHookType("post_complete")).isEqualTo("post_action");
        assertThat(BpmNodeHookService.normalizeHookType("pre_check")).isEqualTo("pre_check");
        assertThat(BpmNodeHookService.normalizeHookType("post_action")).isEqualTo("post_action");
        assertThat(BpmNodeHookService.normalizeHookType("custom_unknown")).isEqualTo("custom_unknown");
        assertThat(BpmNodeHookService.normalizeHookType(null)).isNull();

        // actionType
        assertThat(BpmNodeHookService.normalizeActionType("http_callback")).isEqualTo("rest_call");
        assertThat(BpmNodeHookService.normalizeActionType("rest_call")).isEqualTo("rest_call");
        assertThat(BpmNodeHookService.normalizeActionType("script")).isEqualTo("script");
        assertThat(BpmNodeHookService.normalizeActionType("command")).isEqualTo("command");
        assertThat(BpmNodeHookService.normalizeActionType("drools_rule")).isEqualTo("drools_rule");
        assertThat(BpmNodeHookService.normalizeActionType("unknown_executor")).isEqualTo("unknown_executor");
        assertThat(BpmNodeHookService.normalizeActionType(null)).isNull();
    }
}
