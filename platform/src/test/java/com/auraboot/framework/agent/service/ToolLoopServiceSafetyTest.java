package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.auraboot.framework.agent.authorization.BlastRadius;
import com.auraboot.framework.agent.authorization.EffectClass;
import com.auraboot.framework.agent.authorization.RuntimeAuthorizationService;
import com.auraboot.framework.agent.observability.AgentRuntimeObservabilityService;
import com.auraboot.framework.agent.provider.ProviderExecutionResult;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.SkillResult;
import com.auraboot.framework.aurabot.skill.provider.SkillToolExecutor;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ToolLoopService safety gates")
class ToolLoopServiceSafetyTest {

    @Mock private ActionRecorder actionRecorder;
    @Mock private AgentApprovalGateService approvalGate;
    @Mock private ToolAclChecker toolAclChecker;
    @Mock private AiTraceService aiTraceService;
    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private CommandExecutor commandExecutor;
    @Mock private NamedQueryService namedQueryService;
    @Mock private ToolProviderRegistry toolProviderRegistry;
    @Mock private ResultContractEmitter resultContractEmitter;
    @Mock private RuntimeAuthorizationService runtimeAuthorizationService;
    @Mock private SkillToolExecutor skillToolExecutor;
    @Mock private AgentRuntimeObservabilityService observabilityService;

    private ToolLoopService service;

    @BeforeEach
    void setup() {
        service = new ToolLoopService(
                actionRecorder,
                approvalGate,
                toolAclChecker,
                aiTraceService,
                dynamicDataMapper,
                commandExecutor,
                namedQueryService,
                new ObjectMapper(),
                toolProviderRegistry,
                resultContractEmitter,
                runtimeAuthorizationService);
        when(toolAclChecker.check(anyLong(), nullable(String.class), nullable(String.class), anyString(), anyString()))
                .thenReturn(ToolAclChecker.Decision.builder()
                        .allowed(true)
                        .matchedPriority(-1)
                        .reason("test_allow")
                        .build());
        lenient().when(runtimeAuthorizationService.authorizeIncremental(any()))
                .thenReturn(RuntimeAuthorizationService.IncrementalAuthorization.grant());
        ReflectionTestUtils.setField(service, "observabilityService", observabilityService);
    }

    @Test
    @DisplayName("unsupported discovered tool type returns structured fail-fast signal")
    void unsupportedToolTypeReturnsStructuredFailFastSignal() {
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("vendor.magic")
                .description("Unsupported vendor tool")
                .toolType("vendor_magic")
                .sourceCode("vendor.magic")
                .riskLevel("L1")
                .build();

        String result = service.executeToolCall(1L, "run-unsupported", "task-unsupported", "agent",
                tool.getName(), Map.of("value", "x"), List.of(tool), null);

        assertThat(result)
                .contains("\"success\":false")
                .contains("\"errorCode\":\"unsupported_tool_type\"")
                .contains("\"toolName\":\"vendor.magic\"")
                .contains("\"toolType\":\"vendor_magic\"");
        verify(observabilityService).recordUnsupportedToolType("vendor_magic");
        verify(observabilityService).recordToolExecution("vendor_magic", false, "unsupported_type");
        verifyNoInteractions(toolProviderRegistry, commandExecutor, namedQueryService);
    }

    @AfterEach
    void cleanup() {
        BifContext.clear();
    }

    @Test
    @DisplayName("L2 confirmation-required command does not execute before user confirmation")
    void confirmationRequiredCommandDoesNotExecute() {
        Map<String, Object> input = Map.of("name", "Draft comparison");
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("cmd:pe:create_procurement_comparison_draft")
                .description("Create draft")
                .toolType("dsl_command")
                .sourceCode("pe:create_procurement_comparison_draft")
                .riskLevel("L2")
                .confirmationPolicy("confirm")
                .requiresConfirmation(true)
                .build();

        String result = service.executeToolCall(1L, "run-1", "task-1", "agent",
                tool.getName(), input, List.of(tool), null);

        assertThat(result).contains("requires user confirmation").contains("No data was changed");
        verify(resultContractEmitter).emitConfirmationRequired(eq(tool.getName()), same(tool), eq(input), eq(0L));
        verifyNoInteractions(commandExecutor);
        verify(actionRecorder, never()).recordAction(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("R3 alias in BIF risk escalates colon command tool to approval")
    void r3AliasEscalatesColonCommandToApproval() {
        BifContext.setCurrentBif(BusinessIntentFrame.builder()
                .intent("submit")
                .object("pe_procurement_comparison")
                .riskLevel("r3")
                .actionability("execute")
                .confidence(ConfidenceScore.of(0.9, 0.9))
                .build());
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("cmd:pe:submit_procurement_comparison")
                .description("Submit review")
                .toolType("dsl_command")
                .sourceCode("pe:submit_procurement_comparison")
                .riskLevel("L1")
                .requiresApproval(false)
                .build();
        when(approvalGate.checkAndRequestApproval(eq(1L), eq("run-2"), eq("task-2"), eq(tool.getName()),
                eq(tool.getDescription()), anyMap(), eq(true)))
                .thenReturn("approval-1");

        String result = service.executeToolCall(1L, "run-2", "task-2", "agent",
                tool.getName(), Map.of("pid", "pc-1"), List.of(tool), null);

        assertThat(result).contains("\"approvalRequired\":true").contains("\"approvalPid\":\"approval-1\"");
        verifyNoInteractions(commandExecutor);
    }

    @Test
    @DisplayName("approval-required command does not execute when no policy can create an approval")
    void approvalRequiredCommandWithoutPolicyDoesNotExecute() {
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("cmd:pe:submit_procurement_comparison")
                .description("Submit review")
                .toolType("dsl_command")
                .sourceCode("pe:submit_procurement_comparison")
                .riskLevel("L3")
                .requiresApproval(true)
                .build();
        when(approvalGate.checkAndRequestApproval(eq(1L), eq("run-3"), eq("task-3"), eq(tool.getName()),
                eq(tool.getDescription()), anyMap(), eq(true)))
                .thenReturn(null);

        String result = service.executeToolCall(1L, "run-3", "task-3", "agent",
                tool.getName(), Map.of("pid", "pc-1"), List.of(tool), null);

        assertThat(result).contains("approval policy").contains("No data was changed");
        verifyNoInteractions(commandExecutor);
    }

    @Test
    @DisplayName("platform provider tools route through ToolProviderRegistry")
    void platformProviderToolsRouteThroughRegistry() {
        Map<String, Object> input = Map.of("keyword", "customer");
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("platform.list_models")
                .description("List models")
                .toolType("platform")
                .sourceCode("platform.list_models")
                .riskLevel("L0")
                .build();
        when(toolProviderRegistry.execute(eq(1L), eq("platform.list_models"), eq(input)))
                .thenReturn(ProviderExecutionResult.builder()
                        .success(true)
                        .data(Map.of("models", List.of(Map.of("code", "crm_customer"))))
                        .durationMs(7)
                        .build());

        String result = service.executeToolCall(1L, "run-platform", "task-platform", "agent",
                tool.getName(), input, List.of(tool), null);

        assertThat(result).contains("\"success\":true").contains("crm_customer");
        verify(toolProviderRegistry).execute(1L, "platform.list_models", input);
        verify(resultContractEmitter).emitProviderResult(
                eq("platform.list_models"), same(tool), anyString(), anyLong(), eq(true));
        verify(actionRecorder).recordProviderAction(
                eq(1L), eq("run-platform"), eq("platform.list_models"), same(tool),
                eq(input), anyMap(), isNull(), eq(Set.of(EffectClass.READ_PLATFORM_DATA)));
        verifyNoInteractions(commandExecutor, namedQueryService);
    }

    @Test
    @DisplayName("runtime authorization rejects mutating provider tools before execution")
    void runtimeAuthorizationRejectsMutatingProviderToolBeforeExecution() {
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("platform.create_model")
                .description("Create model")
                .toolType("platform")
                .sourceCode("platform.create_model")
                .riskLevel("L3")
                .requiresApproval(false)
                .build();
        when(runtimeAuthorizationService.authorizeIncremental(any()))
                .thenReturn(RuntimeAuthorizationService.IncrementalAuthorization.reject(
                        "WRITE_PLATFORM_STATE is forbidden", "tenant_policy"));

        String result = service.executeToolCall(1L, "run-authz", "task-authz", "agent",
                tool.getName(), Map.of("description", "Customer model"), List.of(tool), null);

        assertThat(result)
                .contains("Runtime authorization denied")
                .contains("WRITE_PLATFORM_STATE is forbidden");
        verify(runtimeAuthorizationService).authorizeIncremental(argThat(intent ->
                intent.requiredEffects().contains(EffectClass.WRITE_PLATFORM_STATE)
                        && intent.blastRadius() == BlastRadius.SHARED_STATE
                        && "platform.create_model".equals(intent.toolRef())));
        verifyNoInteractions(toolProviderRegistry, commandExecutor, namedQueryService);
    }

    @Test
    @DisplayName("runtime authorization records read provider effects before execution")
    void runtimeAuthorizationRunsForReadProviderTools() {
        Map<String, Object> input = Map.of();
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("platform.list_models")
                .description("List models")
                .toolType("platform")
                .sourceCode("platform.list_models")
                .riskLevel("L0")
                .build();
        when(toolProviderRegistry.execute(eq(1L), eq("platform.list_models"), eq(input)))
                .thenReturn(ProviderExecutionResult.builder()
                        .success(true)
                        .data(Map.of("models", List.of()))
                        .durationMs(3)
                        .build());

        String result = service.executeToolCall(1L, "run-read", "task-read", "agent",
                tool.getName(), input, List.of(tool), null);

        assertThat(result).contains("\"success\":true");
        verify(runtimeAuthorizationService).authorizeIncremental(argThat(intent ->
                intent.requiredEffects().contains(EffectClass.READ_PLATFORM_DATA)
                        && intent.blastRadius() == BlastRadius.REVERSIBLE
                        && "platform.list_models".equals(intent.toolRef())));
    }

    @Test
    @DisplayName("low-risk AuraBot skill tools route through SkillToolExecutor")
    void lowRiskAurabotSkillToolsRouteThroughSkillExecutor() {
        ReflectionTestUtils.setField(service, "skillToolExecutor", skillToolExecutor);
        Map<String, Object> input = Map.of("text", "hello");
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("aurabot:echo")
                .description("Echo")
                .toolType("AURABOT_SKILL")
                .sourceCode("echo")
                .riskLevel("LOW")
                .build();
        when(skillToolExecutor.dispatch(eq("echo"), any(SkillRequest.class)))
                .thenReturn(SkillToolExecutor.DispatchOutcome.executed(
                        SkillResult.builder()
                                .status(SkillResult.Status.SUCCESS)
                                .payload(Map.of("text", "hello"))
                                .build(),
                        RiskLevel.LOW));

        String result = service.executeToolCall(1L, "run-skill", "task-skill", "agent",
                tool.getName(), input, List.of(tool), null);

        assertThat(result).contains("\"success\":true").contains("\"text\":\"hello\"");
        verify(skillToolExecutor).dispatch(eq("echo"), argThat(req ->
                req.getParams() != null && "hello".equals(req.getParams().get("text").asText())));
        verifyNoInteractions(toolProviderRegistry, commandExecutor, namedQueryService);
    }

    @Test
    @DisplayName("model query AuraBot skill is authorized and audited as read-only")
    void modelQueryAurabotSkillIsReadOnly() {
        ReflectionTestUtils.setField(service, "skillToolExecutor", skillToolExecutor);
        Map<String, Object> input = Map.of("keyword", "customer");
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("aurabot:model:query")
                .description("Query model")
                .toolType("AURABOT_SKILL")
                .sourceCode("model:query")
                .riskLevel("low")
                .build();
        when(skillToolExecutor.dispatch(eq("model:query"), any(SkillRequest.class)))
                .thenReturn(SkillToolExecutor.DispatchOutcome.executed(
                        SkillResult.builder()
                                .status(SkillResult.Status.SUCCESS)
                                .payload(Map.of("total", 0))
                                .build(),
                        RiskLevel.LOW));

        String result = service.executeToolCall(1L, "run-skill-query", "task-skill-query", "agent",
                tool.getName(), input, List.of(tool), null);

        assertThat(result).contains("\"success\":true").contains("\"total\":0");
        verify(runtimeAuthorizationService).authorizeIncremental(argThat(intent ->
                intent.requiredEffects().contains(EffectClass.READ_PLATFORM_DATA)
                        && intent.blastRadius() == BlastRadius.REVERSIBLE
                        && "model:query".equals(intent.skillCode())));
        verify(actionRecorder).recordProviderAction(
                eq(1L), eq("run-skill-query"), eq("aurabot:model:query"), same(tool),
                eq(input), anyMap(), isNull(), eq(Set.of(EffectClass.READ_PLATFORM_DATA)));
    }

    @Test
    @DisplayName("high-risk AuraBot skills return preview tokens instead of generic approval requests")
    void highRiskAurabotSkillReturnsPreviewTokenBeforeGenericApprovalGate() {
        ReflectionTestUtils.setField(service, "skillToolExecutor", skillToolExecutor);
        Map<String, Object> input = Map.of("code", "crm_customer");
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("aurabot:model:create")
                .description("Create model")
                .toolType("AURABOT_SKILL")
                .sourceCode("model:create")
                .riskLevel("high")
                .requiresApproval(true)
                .requiresConfirmation(true)
                .build();
        when(skillToolExecutor.dispatch(eq("model:create"), any(SkillRequest.class)))
                .thenReturn(SkillToolExecutor.DispatchOutcome.pending(
                        SkillResult.builder()
                                .status(SkillResult.Status.NEEDS_CONFIRM)
                                .preview(Map.of("modelCode", "crm_customer"))
                                .build(),
                        "preview-1",
                        RiskLevel.HIGH));

        String result = service.executeToolCall(1L, "run-skill-preview", "task-skill-preview", "agent",
                tool.getName(), input, List.of(tool), null);

        assertThat(result)
                .contains("\"approvalRequired\":true")
                .contains("\"previewToken\":\"preview-1\"")
                .contains("crm_customer");
        verify(skillToolExecutor).dispatch(eq("model:create"), any(SkillRequest.class));
        verify(approvalGate, never()).checkAndRequestApproval(any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(resultContractEmitter).emitProviderResult(
                eq("aurabot:model:create"), same(tool), anyString(), anyLong(), eq(false));
        verify(actionRecorder, never()).recordProviderAction(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AuraBot skill confirm routes through ToolLoopService")
    void aurabotSkillConfirmRoutesThroughToolLoopService() {
        ReflectionTestUtils.setField(service, "skillToolExecutor", skillToolExecutor);
        Map<String, Object> input = Map.of("name", "Customer");
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("aurabot:model:create")
                .description("Create model")
                .toolType("AURABOT_SKILL")
                .sourceCode("model:create")
                .riskLevel("high")
                .build();
        when(skillToolExecutor.confirm(eq("model:create"), any(SkillRequest.class), eq("preview-1")))
                .thenReturn(SkillToolExecutor.DispatchOutcome.executed(
                        SkillResult.builder()
                                .status(SkillResult.Status.SUCCESS)
                                .payload(Map.of("modelCode", "crm_customer"))
                                .build(),
                        RiskLevel.HIGH));

        String result = service.confirmAuraBotSkill(1L, "run-skill-confirm", "task-skill-confirm", "agent",
                tool.getName(), input, List.of(tool), "preview-1", null);

        assertThat(result).contains("\"success\":true").contains("crm_customer");
        verify(skillToolExecutor).confirm(eq("model:create"), argThat(req ->
                req.getParams() != null && "Customer".equals(req.getParams().get("name").asText())),
                eq("preview-1"));
        verify(resultContractEmitter).emitProviderResult(
                eq("aurabot:model:create"), same(tool), anyString(), anyLong(), eq(true));
        verify(actionRecorder).recordProviderAction(
                eq(1L), eq("run-skill-confirm"), eq("aurabot:model:create"), same(tool),
                eq(input), anyMap(), isNull(), eq(Set.of(EffectClass.WRITE_PLATFORM_STATE)));
        verify(skillToolExecutor, never()).dispatch(anyString(), any(SkillRequest.class));
        verifyNoInteractions(toolProviderRegistry, commandExecutor, namedQueryService);
    }

    @Test
    @DisplayName("runtime authorization rejects AuraBot skill confirmation before execution")
    void runtimeAuthorizationRejectsAurabotSkillConfirmBeforeExecution() {
        ReflectionTestUtils.setField(service, "skillToolExecutor", skillToolExecutor);
        Map<String, Object> input = Map.of("code", "crm_customer");
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("aurabot:model:create")
                .description("Create model")
                .toolType("AURABOT_SKILL")
                .sourceCode("model:create")
                .riskLevel("high")
                .build();
        when(runtimeAuthorizationService.authorizeIncremental(any()))
                .thenReturn(RuntimeAuthorizationService.IncrementalAuthorization.reject(
                        "WRITE_PLATFORM_STATE is forbidden", "tenant_policy"));

        String result = service.confirmAuraBotSkill(1L, "run-skill-authz", "task-skill-authz", "agent",
                tool.getName(), input, List.of(tool), "preview-1", null);

        assertThat(result)
                .contains("Runtime authorization denied")
                .contains("WRITE_PLATFORM_STATE is forbidden");
        verify(runtimeAuthorizationService).authorizeIncremental(argThat(intent ->
                intent.requiredEffects().contains(EffectClass.WRITE_PLATFORM_STATE)
                        && "aurabot:model:create".equals(intent.toolRef())
                        && "model:create".equals(intent.skillCode())));
        verify(skillToolExecutor, never()).confirm(anyString(), any(SkillRequest.class), anyString());
    }

    @Test
    @DisplayName("custom provider api_call tools route through ToolProviderRegistry")
    void customProviderApiCallToolsRouteThroughRegistry() {
        Map<String, Object> input = Map.of("value", "ping");
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("custom:api_test_tool")
                .description("Custom API tool")
                .toolType("api_call")
                .riskLevel("L1")
                .build();
        when(toolProviderRegistry.execute(eq(1L), eq("custom:api_test_tool"), eq(input)))
                .thenReturn(ProviderExecutionResult.builder()
                        .success(true)
                        .data(Map.of("body", "{\"ok\":true}"))
                        .durationMs(11)
                        .build());

        String result = service.executeToolCall(1L, "run-custom", "task-custom", "agent",
                tool.getName(), input, List.of(tool), null);

        assertThat(result).contains("\"success\":true").contains("\"body\":\"{\\\"ok\\\":true}\"");
        verify(toolProviderRegistry).execute(1L, "custom:api_test_tool", input);
        verifyNoInteractions(commandExecutor, namedQueryService);
    }

    @Test
    @DisplayName("approved state transition command passes target record id")
    void approvedStateTransitionCommandPassesTargetRecordId() {
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("cmd_pe_submit_procurement_comparison")
                .description("Submit review")
                .toolType("dsl_command")
                .sourceCode("pe:submit_procurement_comparison")
                .riskLevel("L3")
                .requiresApproval(false)
                .build();
        when(commandExecutor.execute(eq("pe:submit_procurement_comparison"), any(CommandExecuteRequest.class)))
                .thenReturn(CommandExecuteResult.builder()
                        .data(Map.of("pid", "PC-1", "pe_pc_status", "review_required"))
                        .build());

        String result = service.executeToolCall(1L, "run-4", "task-4", "agent",
                tool.getName(), Map.of("recordId", "PC-1"), List.of(tool), null);

        org.mockito.ArgumentCaptor<CommandExecuteRequest> requestCaptor =
                org.mockito.ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(commandExecutor).execute(eq("pe:submit_procurement_comparison"), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getTargetRecordId()).isEqualTo("PC-1");
        assertThat(result).contains("\"success\":true").contains("review_required");
    }
}
