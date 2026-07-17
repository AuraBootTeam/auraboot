package com.auraboot.framework.automation.trigger.impl;

import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.entity.AutomationLog;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.automation.mapper.AutomationLogMapper;
import com.auraboot.framework.automation.mapper.AutomationMapper;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AutomationTriggerServiceImpl.
 * Tests condition evaluation, watch-field filtering, and field/state change filtering.
 * @Async annotations have no effect without Spring context — methods run synchronously.
 */
@ExtendWith(MockitoExtension.class)
class AutomationTriggerServiceImplTest {

    @Mock
    private AutomationMapper automationMapper;

    @Mock
    private AutomationLogMapper automationLogMapper;

    @Mock
    private com.auraboot.framework.automation.bpm.AutomationProcessRuntime automationProcessRuntime;

    @Mock
    private UserMapper userMapper;

    @Mock
    private TenantMemberService tenantMemberService;

    private AutomationTriggerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AutomationTriggerServiceImpl(
                automationMapper, automationLogMapper, automationProcessRuntime);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "tenantMemberService", tenantMemberService);
    }

    @AfterEach
    void tearDown() {
        com.auraboot.framework.application.tenant.MetaContext.clear();
    }

    // =========================================================
    // evaluateCondition — blank / null
    // =========================================================

    @Test
    void evaluateCondition_nullCondition_returnsTrue() {
        assertThat(service.evaluateCondition(null, Map.of())).isTrue();
    }

    @Test
    void evaluateCondition_emptyCondition_returnsTrue() {
        assertThat(service.evaluateCondition("", Map.of())).isTrue();
    }

    @Test
    void evaluateCondition_blankCondition_returnsTrue() {
        assertThat(service.evaluateCondition("   ", Map.of())).isTrue();
    }

    // =========================================================
    // evaluateCondition — simple literals
    // =========================================================

    @Test
    void evaluateCondition_literalTrue_returnsTrue() {
        assertThat(service.evaluateCondition("true", Map.of())).isTrue();
    }

    @Test
    void evaluateCondition_literalFalse_returnsFalse() {
        assertThat(service.evaluateCondition("false", Map.of())).isFalse();
    }

    // =========================================================
    // evaluateCondition — context variable binding
    // =========================================================

    @Test
    void evaluateCondition_variableEquality_evaluatesCorrectly() {
        Map<String, Object> context = Map.of("status", "active");
        assertThat(service.evaluateCondition("#status == 'active'", context)).isTrue();
    }

    @Test
    void evaluateCondition_variableEquality_wrongValue_returnsFalse() {
        Map<String, Object> context = Map.of("status", "inactive");
        assertThat(service.evaluateCondition("#status == 'active'", context)).isFalse();
    }

    @Test
    void evaluateCondition_numericComparison_returnsTrue() {
        Map<String, Object> context = Map.of("amount", 500);
        assertThat(service.evaluateCondition("#amount > 100", context)).isTrue();
    }

    @Test
    void evaluateCondition_numericComparison_returnsFalse() {
        Map<String, Object> context = Map.of("amount", 50);
        assertThat(service.evaluateCondition("#amount > 100", context)).isFalse();
    }

    // =========================================================
    // evaluateCondition — security: dangerous expressions
    // =========================================================

    @Test
    void evaluateCondition_dangerousExpression_T_returnsFalse() {
        assertThat(service.evaluateCondition("T(java.lang.Runtime).getRuntime()", Map.of())).isFalse();
    }

    @Test
    void evaluateCondition_dangerousExpression_new_returnsFalse() {
        assertThat(service.evaluateCondition("new java.lang.ProcessBuilder('ls').start()", Map.of())).isFalse();
    }

    @Test
    void evaluateCondition_dangerousExpression_getClass_returnsFalse() {
        assertThat(service.evaluateCondition("#root.getClass()", Map.of())).isFalse();
    }

    @Test
    void evaluateCondition_dangerousExpression_systemExit_returnsFalse() {
        assertThat(service.evaluateCondition("T(System).exit(0)", Map.of())).isFalse();
    }

    // =========================================================
    // evaluateCondition — security: length limit
    // =========================================================

    @Test
    void evaluateCondition_exceedsMaxLength_returnsFalse() {
        String longExpression = "true".repeat(200); // > 500 chars
        assertThat(service.evaluateCondition(longExpression, Map.of())).isFalse();
    }

    // =========================================================
    // evaluateCondition — invalid SpEL
    // =========================================================

    @Test
    void evaluateCondition_invalidSpel_returnsFalse() {
        assertThat(service.evaluateCondition("{{{{ not valid spel }", Map.of())).isFalse();
    }

    // =========================================================
    // executeAutomation — tenant context scoping (webhook / system path)
    // =========================================================

    @Test
    void executeAutomation_noCallerContext_establishesAndClearsTenantContext() {
        // A webhook-triggered automation reaches executeAutomation on a JWT-exempt thread with
        // no MetaContext. The whole method (insertLog, run, updateStatus) must be tenant-scoped
        // from automation.tenantId, else AutomationLogMapper fails with "MetaContext not
        // initialized". Regression for the webhook-automation 500.
        com.auraboot.framework.application.tenant.MetaContext.clear();
        assertThat(com.auraboot.framework.application.tenant.MetaContext.exists()).isFalse();

        Automation automation = new Automation();
        automation.setPid("AUTO-WH-1");
        automation.setTenantId(424242L);
        automation.setTriggerType("webhook");

        // Assert the tenant context is live at run() time (i.e. during insert + the command pipeline).
        final boolean[] contextDuringRun = {false};
        final Long[] tenantDuringRun = {null};
        doAnswer(inv -> {
            contextDuringRun[0] = com.auraboot.framework.application.tenant.MetaContext.exists();
            tenantDuringRun[0] = com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId();
            return null;
        }).when(automationProcessRuntime).run(any(), any(), any(), any());

        AutomationLog result = service.executeAutomation(automation, null, Map.of("event", "webhook"));

        assertThat(contextDuringRun[0]).isTrue();
        assertThat(tenantDuringRun[0]).isEqualTo(424242L);
        assertThat(result.getStatus()).isEqualTo("success");
        verify(automationLogMapper).insertLog(any());
        verify(automationLogMapper).updateStatus(any());
        // We set the context, so we must have cleared it on the way out.
        assertThat(com.auraboot.framework.application.tenant.MetaContext.exists()).isFalse();
    }

    @Test
    void executeAutomation_noCallerContext_restoresActorUserAndMemberContext() {
        com.auraboot.framework.application.tenant.MetaContext.clear();

        Automation automation = new Automation();
        automation.setPid("AUTO-ASYNC-1");
        automation.setTenantId(7L);
        automation.setTriggerType("on_record_create");
        automation.setCreatedBy("USER-PID-1");

        User user = new User();
        user.setId(99L);
        user.setPid("USER-PID-1");
        user.setEmail("actor@example.com");
        TenantMember member = new TenantMember();
        member.setId(123L);
        member.setTenantId(7L);
        member.setUserId(99L);

        when(userMapper.findUserIdInTenantByPid(7L, "USER-PID-1")).thenReturn(99L);
        when(userMapper.selectById(99L)).thenReturn(user);
        when(tenantMemberService.findByTenantIdAndUserId(7L, 99L)).thenReturn(member);

        final Long[] tenantDuringRun = {null};
        final Long[] userDuringRun = {null};
        final String[] userPidDuringRun = {null};
        final Long[] memberDuringRun = {null};
        doAnswer(inv -> {
            tenantDuringRun[0] = com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId();
            userDuringRun[0] = com.auraboot.framework.application.tenant.MetaContext.getCurrentUserId();
            userPidDuringRun[0] = com.auraboot.framework.application.tenant.MetaContext.getCurrentUserPid();
            memberDuringRun[0] = com.auraboot.framework.application.tenant.MetaContext.getCurrentMemberId();
            return List.of();
        }).when(automationProcessRuntime).run(any(), any(), any(), any());

        AutomationLog result = service.executeAutomation(automation, "rec-1", Map.of("event", "create"));

        assertThat(tenantDuringRun[0]).isEqualTo(7L);
        assertThat(userDuringRun[0]).isEqualTo(99L);
        assertThat(userPidDuringRun[0]).isEqualTo("USER-PID-1");
        assertThat(memberDuringRun[0]).isEqualTo(123L);
        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(com.auraboot.framework.application.tenant.MetaContext.exists()).isFalse();
    }

    @Test
    void executeAutomation_tenantOnlyCallerContext_upgradesActorContextAndRestoresTenantOnlyContext() {
        com.auraboot.framework.application.tenant.MetaContext.setSystemTenantContext(7L);
        com.auraboot.framework.application.tenant.MetaContext.setEnvironmentId(88L);
        com.auraboot.framework.application.tenant.MetaContext.setOtelTraceId("trace-tenant-only");

        Automation automation = new Automation();
        automation.setPid("AUTO-ASYNC-TENANT-ONLY");
        automation.setTenantId(7L);
        automation.setTriggerType("on_record_create");
        automation.setCreatedBy("USER-PID-1");

        User user = new User();
        user.setId(99L);
        user.setPid("USER-PID-1");
        user.setUserName("automation-owner");
        TenantMember member = new TenantMember();
        member.setId(123L);
        member.setTenantId(7L);
        member.setUserId(99L);

        when(userMapper.findUserIdInTenantByPid(7L, "USER-PID-1")).thenReturn(99L);
        when(userMapper.selectById(99L)).thenReturn(user);
        when(tenantMemberService.findByTenantIdAndUserId(7L, 99L)).thenReturn(member);

        final Long[] tenantDuringRun = {null};
        final Long[] userDuringRun = {null};
        final String[] usernameDuringRun = {null};
        final Long[] memberDuringRun = {null};
        doAnswer(inv -> {
            tenantDuringRun[0] = com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId();
            userDuringRun[0] = com.auraboot.framework.application.tenant.MetaContext.getCurrentUserId();
            usernameDuringRun[0] = com.auraboot.framework.application.tenant.MetaContext.getCurrentUsername();
            memberDuringRun[0] = com.auraboot.framework.application.tenant.MetaContext.getCurrentMemberId();
            return List.of();
        }).when(automationProcessRuntime).run(any(), any(), any(), any());

        AutomationLog result = service.executeAutomation(automation, "rec-tenant-only", Map.of("event", "create"));

        assertThat(tenantDuringRun[0]).isEqualTo(7L);
        assertThat(userDuringRun[0]).isEqualTo(99L);
        assertThat(usernameDuringRun[0]).isEqualTo("automation-owner");
        assertThat(memberDuringRun[0]).isEqualTo(123L);
        assertThat(result.getStatus()).isEqualTo("success");

        assertThat(com.auraboot.framework.application.tenant.MetaContext.exists()).isTrue();
        assertThat(com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId()).isEqualTo(7L);
        assertThat(com.auraboot.framework.application.tenant.MetaContext.getCurrentUserId()).isNull();
        assertThat(com.auraboot.framework.application.tenant.MetaContext.getCurrentMemberId()).isNull();
        assertThat(com.auraboot.framework.application.tenant.MetaContext.getCurrentEnvironmentId()).isEqualTo(88L);
        assertThat(com.auraboot.framework.application.tenant.MetaContext.getOtelTraceId()).isEqualTo("trace-tenant-only");
    }

    @Test
    void executeAutomation_existingCallerContext_isLeftIntact() {
        // The record-trigger path already carries a MetaContext (the firing user). executeAutomation
        // must not clear a context it did not create.
        com.auraboot.framework.application.tenant.MetaContext.setContext(7L, 99L, "user-pid", "alice");
        com.auraboot.framework.application.tenant.MetaContext.setMemberId(123L);
        try {
            Automation automation = new Automation();
            automation.setPid("AUTO-REC-1");
            automation.setTenantId(7L);
            automation.setTriggerType("on_record_create");

            service.executeAutomation(automation, "rec-1", Map.of("event", "create"));

            // Caller's context survives.
            assertThat(com.auraboot.framework.application.tenant.MetaContext.exists()).isTrue();
            assertThat(com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId()).isEqualTo(7L);
            assertThat(com.auraboot.framework.application.tenant.MetaContext.getCurrentUserId()).isEqualTo(99L);
            assertThat(com.auraboot.framework.application.tenant.MetaContext.getCurrentMemberId()).isEqualTo(123L);
            verify(userMapper, never()).findUserIdInTenantByPid(any(), any());
        } finally {
            com.auraboot.framework.application.tenant.MetaContext.clear();
        }
    }

    // =========================================================
    // onRecordUpdate — watch fields filtering
    // =========================================================

    @Test
    void onRecordUpdate_noWatchFields_alwaysTriggers() {
        TriggerConfig config = new TriggerConfig();
        config.setWatchFields(null);
        Automation automation = buildAutomation("auto-001", "model-A", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-A", "on_record_update"))
                .thenReturn(List.of(automation));

        Map<String, Object> before = Map.of("status", "draft");
        Map<String, Object> after = Map.of("status", "draft"); // same value — no change

        service.onRecordUpdate("model-A", "rec-001", before, after);

        // No watch fields → always triggers regardless of data change
        verify(automationLogMapper).insertLog(any());
    }

    @Test
    void onRecordUpdate_watchedFieldChanged_triggers() {
        TriggerConfig config = new TriggerConfig();
        config.setWatchFields(List.of("status"));
        Automation automation = buildAutomation("auto-002", "model-B", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-B", "on_record_update"))
                .thenReturn(List.of(automation));

        Map<String, Object> before = Map.of("status", "draft");
        Map<String, Object> after = Map.of("status", "active"); // changed

        service.onRecordUpdate("model-B", "rec-002", before, after);

        verify(automationLogMapper).insertLog(any());
    }

    @Test
    void onRecordUpdate_watchedFieldNotChanged_doesNotTrigger() {
        TriggerConfig config = new TriggerConfig();
        config.setWatchFields(List.of("status"));
        Automation automation = buildAutomation("auto-003", "model-C", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-C", "on_record_update"))
                .thenReturn(List.of(automation));

        Map<String, Object> before = Map.of("status", "draft");
        Map<String, Object> after = Map.of("status", "draft"); // unchanged

        service.onRecordUpdate("model-C", "rec-003", before, after);

        // Should NOT have created a log (no trigger)
        verify(automationLogMapper, never()).insertLog(any());
    }

    // =========================================================
    // onFieldChange — from/to value constraints
    // =========================================================

    @Test
    void onFieldChange_matchesFieldCodeAndFromTo_triggers() {
        TriggerConfig config = new TriggerConfig();
        config.setFieldCode("priority");
        config.setFromValue("low");
        config.setToValue("high");
        Automation automation = buildAutomation("auto-004", "model-D", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-D", "on_field_change"))
                .thenReturn(List.of(automation));

        service.onFieldChange("model-D", "rec-004", "priority", "low", "high");

        verify(automationLogMapper).insertLog(any());
    }

    @Test
    void onFieldChange_wrongFieldCode_doesNotTrigger() {
        TriggerConfig config = new TriggerConfig();
        config.setFieldCode("status");
        Automation automation = buildAutomation("auto-005", "model-E", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-E", "on_field_change"))
                .thenReturn(List.of(automation));

        service.onFieldChange("model-E", "rec-005", "priority", "low", "high"); // wrong field

        verify(automationLogMapper, never()).insertLog(any());
    }

    @Test
    void onFieldChange_fromValueMismatch_doesNotTrigger() {
        TriggerConfig config = new TriggerConfig();
        config.setFieldCode("priority");
        config.setFromValue("medium"); // expects MEDIUM, but actual was LOW
        Automation automation = buildAutomation("auto-006", "model-F", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-F", "on_field_change"))
                .thenReturn(List.of(automation));

        service.onFieldChange("model-F", "rec-006", "priority", "low", "high");

        verify(automationLogMapper, never()).insertLog(any());
    }

    @Test
    void onFieldChange_toValueMismatch_doesNotTrigger() {
        TriggerConfig config = new TriggerConfig();
        config.setFieldCode("priority");
        config.setToValue("critical"); // expects CRITICAL, but actual was HIGH
        Automation automation = buildAutomation("auto-007", "model-G", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-G", "on_field_change"))
                .thenReturn(List.of(automation));

        service.onFieldChange("model-G", "rec-007", "priority", "low", "high");

        verify(automationLogMapper, never()).insertLog(any());
    }

    // =========================================================
    // onStateChange — from/to state constraints
    // =========================================================

    @Test
    void onStateChange_matchesFromAndToStates_triggers() {
        TriggerConfig config = new TriggerConfig();
        config.setFromStates(List.of("draft", "pending"));
        config.setToStates(List.of("active"));
        Automation automation = buildAutomation("auto-008", "model-H", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-H", "on_state_change"))
                .thenReturn(List.of(automation));

        service.onStateChange("model-H", "rec-008", "draft", "active");

        verify(automationLogMapper).insertLog(any());
    }

    @Test
    void onStateChange_fromStateNotInList_doesNotTrigger() {
        TriggerConfig config = new TriggerConfig();
        config.setFromStates(List.of("pending"));
        config.setToStates(List.of("active"));
        Automation automation = buildAutomation("auto-009", "model-I", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-I", "on_state_change"))
                .thenReturn(List.of(automation));

        service.onStateChange("model-I", "rec-009", "draft", "active"); // DRAFT not in fromStates

        verify(automationLogMapper, never()).insertLog(any());
    }

    @Test
    void onStateChange_toStateNotInList_doesNotTrigger() {
        TriggerConfig config = new TriggerConfig();
        config.setFromStates(List.of("draft"));
        config.setToStates(List.of("active"));
        Automation automation = buildAutomation("auto-010", "model-J", null, config, List.of());

        when(automationMapper.findEnabledByModelCodeAndTriggerType("model-J", "on_state_change"))
                .thenReturn(List.of(automation));

        service.onStateChange("model-J", "rec-010", "draft", "suspended"); // SUSPENDED not in toStates

        verify(automationLogMapper, never()).insertLog(any());
    }

    // =========================================================
    // onBpmEvent — SmartEngine process key matching
    // =========================================================

    @Test
    void onBpmEvent_versionedSmartEngineProcessKeyMatchesBareAutomationModelCode() {
        TriggerConfig config = new TriggerConfig();
        config.setEventTypes(List.of("task_assigned"));
        Automation automation = buildAutomation("auto-bpm-001", "e2et_payment_approval", null, config, List.of());
        automation.setTriggerType("on_bpm_event");

        when(automationMapper.findEnabledByModelCodeAndTriggerType("e2et_payment_approval", "on_bpm_event"))
                .thenReturn(List.of(automation));

        service.onBpmEvent("task_assigned", "e2et_payment_approval:1", "pi-001",
                Map.of("taskInstanceId", "task-001"));

        verify(automationMapper).findEnabledByModelCodeAndTriggerType("e2et_payment_approval", "on_bpm_event");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(automationProcessRuntime).run(eq(automation), eq("pi-001"), payloadCaptor.capture(), any());
        assertThat(payloadCaptor.getValue())
                .containsEntry("event", "bpm_event")
                .containsEntry("eventType", "task_assigned")
                .containsEntry("processKey", "e2et_payment_approval:1")
                .containsEntry("instanceId", "pi-001")
                .containsEntry("taskInstanceId", "task-001");
    }

    // =========================================================
    // executeAutomation — actions in sequence
    // =========================================================

    @Test
    void executeAutomation_actionsOnly_routesToProcessRuntime_afterCutover() {
        // After the T2 cutover, actions-only automations also run on SmartEngine via the
        // runtime (the compiler synthesizes a flow from actions[]), not the flat loop.
        AutomationAction action = AutomationAction.builder().type("send_notification").sequence(1).build();
        Automation automation = buildAutomation("auto-011", "model-K", null, null,
                new java.util.ArrayList<>(List.of(action)));

        AutomationLog log = service.executeAutomation(automation, "rec-011", Map.of());

        // run(...) takes 4 args since the G5 overlay (#318): (automation, recordPid,
        // triggerPayload, automationLogId). The log id is null in this DB-less unit test,
        // so the untyped any() matches it.
        verify(automationProcessRuntime).run(eq(automation), eq("rec-011"), any(), any());
        assertThat(log.getStatus()).isEqualTo("success");
    }

    // =========================================================
    // Helper
    // =========================================================

    @Test
    void executeAutomation_withFlowConfig_runsViaProcessRuntime_notFlatActions() {
        Automation automation = new Automation();
        automation.setPid("auto-flow-1");
        automation.setModelCode("model-F");
        automation.setTenantId(1L);
        automation.setFlowConfig(java.util.Map.of(
                "nodes", List.of(
                        java.util.Map.of("id", "t1", "type", "trigger-record-create"),
                        java.util.Map.of("id", "a1", "type", "action-send-notification")),
                "edges", List.of(java.util.Map.of("source", "t1", "target", "a1"))));
        // also give it flat actions to prove they are NOT used when a flow is present
        automation.setActions(new java.util.ArrayList<>(
                List.of(AutomationAction.builder().type("send_notification").sequence(1).build())));

        AutomationLog log = service.executeAutomation(automation, "rec-F", Map.of("event", "create"));

        verify(automationProcessRuntime).run(eq(automation), eq("rec-F"), any(), any());
        assertThat(log.getStatus()).isEqualTo("success");
    }

    private Automation buildAutomation(String pid, String modelCode, String condition,
                                        TriggerConfig triggerConfig, List<AutomationAction> actions) {
        Automation automation = new Automation();
        automation.setPid(pid);
        automation.setModelCode(modelCode);
        automation.setTriggerCondition(condition);
        automation.setTriggerConfig(triggerConfig);
        // Use mutable list — executeAutomation calls actions.sort() which requires mutability
        automation.setActions(actions != null ? new java.util.ArrayList<>(actions) : null);
        automation.setTenantId(1L);
        return automation;
    }
}
