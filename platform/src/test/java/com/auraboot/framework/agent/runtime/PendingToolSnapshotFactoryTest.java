package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.runtime.context.AgentContextAssembler;
import com.auraboot.framework.agent.runtime.context.AgentContextBundle;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.conversation.TurnContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PendingToolSnapshotFactory")
class PendingToolSnapshotFactoryTest {

    private final PendingToolSnapshotFactory factory =
            new PendingToolSnapshotFactory(new AgentRuntimeStateFactory());

    @Test
    @DisplayName("builds chat pending snapshots with identity, tool snapshot and secret-free runtime state")
    @SuppressWarnings("unchecked")
    void buildsChatPendingSnapshotWithRuntimeState() {
        TurnContext ctx = new TurnContext(
                "turn-1",
                1L,
                100L,
                200L,
                300L,
                "agent-a",
                "web",
                "profile-1",
                "channel-1",
                400L,
                500L,
                null,
                java.util.Set.of(),
                "trace-1",
                "task-1",
                Instant.parse("2026-05-19T00:00:00Z"));
        List<LlmChatRequest.Message> messages = List.of(LlmChatRequest.Message.text("user", "Create a draft"));
        ToolDefinition tool = ToolDefinition.builder()
                .toolCode("cmd_create_draft")
                .description("Create draft")
                .toolType("dsl_command")
                .sourceCode("pe:create_draft")
                .riskLevel("L2")
                .requiredPermissions(Set.of("crm.customer.create"))
                .confirmationPolicy("confirm")
                .requiresConfirmation(true)
                .parameterSchema(Map.of("type", "object"))
                .build();

        PendingToolSnapshot pending = factory.build(PendingToolSnapshotFactory.Snapshot.builder()
                .ctx(ctx)
                .agentCode("agent-a")
                .sessionId("session-1")
                .toolId("toolu-1")
                .toolName("cmd_create_draft")
                .toolSpanId("span-1")
                .input(Map.of("productId", "P-100"))
                .description("Create draft for P-100")
                .toolDefinitions(List.of(tool))
                .messages(messages)
                .providerCode("openai")
                .model("gpt-test")
                .systemPrompt("Stored system prompt")
                .runtimeSystemPrompt("Runtime system prompt with provider key sk-secret")
                .maxTokens(2048)
                .currentLoop(2)
                .toolChoice("required")
                .build());

        assertThat(pending.getTurnId()).isEqualTo("turn-1");
        assertThat(pending.getTenantId()).isEqualTo(1L);
        assertThat(pending.getUserId()).isEqualTo(100L);
        assertThat(pending.getHumanMemberId()).isEqualTo(200L);
        assertThat(pending.getConversationId()).isEqualTo(400L);
        assertThat(pending.getAgentCode()).isEqualTo("agent-a");
        assertThat(pending.getSessionId()).isEqualTo("session-1");
        assertThat(pending.getChannel()).isEqualTo("web");
        assertThat(pending.getProfileId()).isEqualTo("profile-1");
        assertThat(pending.getChannelSessionPid()).isEqualTo("channel-1");
        assertThat(pending.getToolId()).isEqualTo("toolu-1");
        assertThat(pending.getToolName()).isEqualTo("cmd_create_draft");
        assertThat(pending.getToolSpanId()).isEqualTo("span-1");
        assertThat(pending.getInput()).isEqualTo(Map.of("productId", "P-100"));
        assertThat(pending.getDescription()).isEqualTo("Create draft for P-100");
        assertThat(pending.getRunPid()).isEqualTo("turn-1");
        assertThat(pending.getTaskPid()).isEqualTo("task-1");
        assertThat(pending.getMessages()).hasSize(1);
        assertThat(pending.getProviderCode()).isEqualTo("openai");
        assertThat(pending.getModel()).isEqualTo("gpt-test");
        assertThat(pending.getSystemPrompt()).isEqualTo("Stored system prompt");
        assertThat(pending.getMaxTokens()).isEqualTo(2048);
        assertThat(pending.getCurrentLoop()).isEqualTo(2);
        assertThat(pending.getApiKey()).isNull();
        assertThat(pending.getBaseUrl()).isNull();
        assertThat(pending.getToolVersion()).isEqualTo("v1");
        assertThat(pending.getArgsHash()).hasSize(64);
        assertThat(pending.getToolSchemaHash()).hasSize(64);
        assertThat(pending.getIdempotencyKey()).isEqualTo(
                "cmd_create_draft:v1:" + pending.getArgsHash());
        assertThat(pending.getExpiresAt()).isGreaterThan(pending.getCreatedAt());
        assertThat(pending.getPolicyDecisionReason()).isEqualTo("user_confirmation_required");
        assertThat(pending.getPreview()).isEqualTo("Create draft for P-100");
        assertThat(pending.getPreviewHash()).hasSize(64);

        assertThat(pending.getAgentToolDefinitions()).hasSize(1);
        AgentToolDefinition storedTool = pending.getAgentToolDefinitions().get(0);
        assertThat(storedTool.getName()).isEqualTo("cmd_create_draft");
        assertThat(storedTool.getSourceCode()).isEqualTo("pe:create_draft");
        assertThat(storedTool.isRequiresConfirmation()).isTrue();
        assertThat(storedTool.getRequiredPermissions()).containsExactly("crm.customer.create");

        assertThat(pending.getExtension()).containsKey("_runtime_state");
        Map<String, Object> runtimeState = (Map<String, Object>) pending.getExtension().get("_runtime_state");
        assertThat(runtimeState)
                .containsEntry("executionKind", "chat_turn")
                .containsEntry("agentCode", "agent-a")
                .containsEntry("providerCode", "openai")
                .containsEntry("model", "gpt-test")
                .containsEntry("round", 2)
                .containsEntry("toolChoice", "required");
        assertThat(String.valueOf(runtimeState))
                .doesNotContain("sk-secret")
                .doesNotContain("Runtime system prompt");
    }

    @Test
    @DisplayName("can build continuation snapshots from an existing pending basis")
    void buildsContinuationSnapshotFromBasis() {
        PendingToolSnapshot basis = PendingToolSnapshot.builder()
                .turnId("turn-original")
                .tenantId(1L)
                .userId(100L)
                .humanMemberId(200L)
                .conversationId(400L)
                .agentCode("agent-a")
                .sessionId("session-1")
                .channelSessionPid("channel-original")
                .modelCode("crm_customer")
                .runPid("run-1")
                .taskPid("task-1")
                .agentToolDefinitions(List.of(AgentToolDefinition.builder()
                        .name("aurabot_model_create")
                        .toolType("AURABOT_SKILL")
                        .build()))
                .providerCode("openai")
                .model("gpt-test")
                .systemPrompt("Stored system prompt")
                .maxTokens(4096)
                .currentLoop(3)
                .build();
        TurnContext resumeCtx = new TurnContext(
                "turn-resume",
                1L,
                100L,
                200L,
                300L,
                "agent-a",
                "channel-resume",
                400L,
                500L,
                null,
                java.util.Set.of(),
                "trace-2",
                "task-resume",
                Instant.parse("2026-05-19T00:01:00Z"));

        PendingToolSnapshot pending = factory.buildFromBasis(
                basis,
                PendingToolSnapshotFactory.BasisSnapshot.builder()
                        .ctx(resumeCtx)
                        .toolId("toolu-next")
                        .toolName("aurabot_model_create")
                        .input(Map.of("code", "crm_customer"))
                        .description("Execute: aurabot_model_create")
                        .messages(List.of(LlmChatRequest.Message.text("user", "Create model")))
                        .currentLoop(5)
                        .extension(Map.of("_aurabot_skill", true, "previewToken", "preview-1"))
                        .build());

        assertThat(pending.getTurnId()).isEqualTo("turn-resume");
        assertThat(pending.getAgentCode()).isEqualTo("agent-a");
        assertThat(pending.getSessionId()).isEqualTo("session-1");
        assertThat(pending.getChannelSessionPid()).isEqualTo("channel-resume");
        assertThat(pending.getModelCode()).isEqualTo("crm_customer");
        assertThat(pending.getRunPid()).isEqualTo("run-1");
        assertThat(pending.getTaskPid()).isEqualTo("task-1");
        assertThat(pending.getProviderCode()).isEqualTo("openai");
        assertThat(pending.getModel()).isEqualTo("gpt-test");
        assertThat(pending.getCurrentLoop()).isEqualTo(5);
        assertThat(pending.getAgentToolDefinitions()).hasSize(1);
        assertThat(pending.getExtension())
                .containsEntry("_aurabot_skill", true)
                .containsEntry("previewToken", "preview-1");
        assertThat(pending.getToolVersion()).isEqualTo("v1");
        assertThat(pending.getArgsHash()).hasSize(64);
        assertThat(pending.getIdempotencyKey()).isEqualTo(
                "aurabot_model_create:v1:" + pending.getArgsHash());
        assertThat(pending.getExpiresAt()).isGreaterThan(pending.getCreatedAt());
        assertThat(pending.getPolicyDecisionReason()).isEqualTo("user_confirmation_required");
        assertThat(pending.getPreview()).isEqualTo("Execute: aurabot_model_create");
        assertThat(pending.getPreviewHash()).hasSize(64);
    }

    @Test
    @DisplayName("preserves explicit policy decision fields when runtime supplies them")
    void preservesExplicitPolicyDecisionFields() {
        TurnContext ctx = new TurnContext(
                "turn-policy",
                1L,
                100L,
                200L,
                300L,
                "agent-a",
                "channel-1",
                400L,
                500L,
                null,
                java.util.Set.of(),
                "trace-1",
                "task-1",
                Instant.parse("2026-05-19T00:00:00Z"));

        PendingToolSnapshot pending = factory.build(PendingToolSnapshotFactory.Snapshot.builder()
                .ctx(ctx)
                .agentCode("agent-a")
                .sessionId("session-1")
                .toolId("toolu-1")
                .toolName("cmd_create_draft")
                .input(Map.of("productId", "P-100"))
                .description("fallback description")
                .toolVersion("v2")
                .argsHash("hash-1")
                .idempotencyKey("idem-1")
                .expiresAt(1_800_000L)
                .policyDecisionReason("user_confirmation_required")
                .toolSchemaHash("schema-hash-1")
                .preview("Preview shown to user")
                .previewHash("preview-hash-1")
                .build());

        assertThat(pending.getToolVersion()).isEqualTo("v2");
        assertThat(pending.getArgsHash()).isEqualTo("hash-1");
        assertThat(pending.getIdempotencyKey()).isEqualTo("idem-1");
        assertThat(pending.getExpiresAt()).isEqualTo(1_800_000L);
        assertThat(pending.getPolicyDecisionReason()).isEqualTo("user_confirmation_required");
        assertThat(pending.getToolSchemaHash()).isEqualTo("schema-hash-1");
        assertThat(pending.getPreview()).isEqualTo("Preview shown to user");
        assertThat(pending.getPreviewHash()).isEqualTo("preview-hash-1");
    }

    @Test
    @DisplayName("adds resolvable context version metadata for record-scoped pending tools")
    void addsResolvableContextVersionMetadata() {
        PendingContextVersionResolver resolver = request -> {
            assertThat(request.tenantId()).isEqualTo(1L);
            assertThat(request.modelCode()).isEqualTo("crm_customer");
            assertThat(request.recordPid()).isEqualTo("C-100");
            return new PendingContextVersion(
                    "crm_customer",
                    "C-100",
                    "change:42",
                    "crm_customer:C-100:change:42");
        };
        PendingToolSnapshotFactory contextAwareFactory =
                new PendingToolSnapshotFactory(new AgentRuntimeStateFactory(), resolver);
        TurnContext ctx = new TurnContext(
                "turn-context",
                1L,
                100L,
                200L,
                300L,
                "agent-a",
                "channel-1",
                400L,
                500L,
                null,
                java.util.Set.of(),
                "trace-1",
                "task-1",
                Instant.parse("2026-05-19T00:00:00Z"));

        PendingToolSnapshot pending = contextAwareFactory.build(PendingToolSnapshotFactory.Snapshot.builder()
                .ctx(ctx)
                .agentCode("agent-a")
                .sessionId("session-1")
                .toolId("toolu-1")
                .toolName("cmd_update_customer")
                .modelCode("crm_customer")
                .input(Map.of("recordPid", "C-100", "name", "Acme"))
                .description("Update customer")
                .build());

        assertThat(pending.getModelCode()).isEqualTo("crm_customer");
        assertThat(pending.getInput()).containsEntry("recordPid", "C-100");
        assertThat(pending.getRecordVersion()).isEqualTo("change:42");
        assertThat(pending.getContextVersion()).isEqualTo("crm_customer:C-100:change:42");
        assertThat(pending.getContextConflictPolicy())
                .isEqualTo(ContextConflictPolicy.REJECT_AND_REPLAN.name());
    }

    @Test
    @DisplayName("uses context provenance record scope before tool-name or input inference")
    void usesContextProvenanceForRecordScopedPendingTools() {
        PendingContextVersionResolver resolver = request -> {
            assertThat(request.tenantId()).isEqualTo(1L);
            assertThat(request.modelCode()).isEqualTo("crm_customer");
            assertThat(request.recordPid()).isEqualTo("C-200");
            return new PendingContextVersion(
                    "crm_customer",
                    "C-200",
                    "change:77",
                    "crm_customer:C-200:change:77");
        };
        PendingToolSnapshotFactory contextAwareFactory =
                new PendingToolSnapshotFactory(new AgentRuntimeStateFactory(), resolver);
        TurnContext ctx = new TurnContext(
                "turn-provenance",
                1L,
                100L,
                200L,
                300L,
                "agent-a",
                "web",
                "profile-1",
                "channel-1",
                400L,
                500L,
                null,
                java.util.Set.of(),
                "trace-1",
                "task-1",
                Instant.parse("2026-05-19T00:00:00Z"));
        ChatRequest.PageContext pageContext = new ChatRequest.PageContext();
        pageContext.setKind("detail");
        pageContext.setModelCode("crm_customer");
        pageContext.setRecordPid("C-200");
        pageContext.setRecordData(Map.of("name", "Acme"));
        AgentContextBundle contextBundle = new AgentContextAssembler(null).assemble(
                new AgentContextAssembler.Request(
                        1L,
                        "web",
                        pageContext,
                        "name:text",
                        null,
                        List.of()));

        PendingToolSnapshot pending = contextAwareFactory.build(PendingToolSnapshotFactory.Snapshot.builder()
                .ctx(ctx)
                .agentCode("agent-a")
                .sessionId("session-1")
                .toolId("toolu-1")
                .toolName("cmd_update_customer")
                .input(Map.of("name", "Acme Updated"))
                .description("Update customer")
                .contextBlocks(contextBundle.blocks())
                .build());

        assertThat(pending.getModelCode()).isEqualTo("crm_customer");
        assertThat(pending.getRecordVersion()).isEqualTo("change:77");
        assertThat(pending.getContextVersion()).isEqualTo("crm_customer:C-200:change:77");
        assertThat(pending.getContextConflictPolicy())
                .isEqualTo(ContextConflictPolicy.REJECT_AND_REPLAN.name());
    }
}
