package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.conversation.TurnContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentRuntimeStateFactory")
class AgentRuntimeStateFactoryTest {

    private final AgentRuntimeStateFactory factory = new AgentRuntimeStateFactory();

    @Test
    @DisplayName("builds a deterministic, secret-free chat turn state")
    void chatTurnStateBuildsDeterministicSecretFreeSnapshot() {
        TurnContext ctx = TurnContext.legacyDefault(7L, 100L, 100L);
        List<LlmChatRequest.Message> messages = List.of(
                LlmChatRequest.Message.text("user", "please create a draft for P-100"),
                LlmChatRequest.Message.text("assistant", "I will inspect supplier data first"));
        List<LlmChatRequest.Tool> llmTools = List.of(LlmChatRequest.Tool.builder()
                .name("cmd_pe_create_procurement_comparison_draft")
                .description("Create comparison draft")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("productId", Map.of("type", "string"))))
                .build());
        List<ToolDefinition> toolDefinitions = List.of(ToolDefinition.builder()
                .toolCode("cmd_pe_create_procurement_comparison_draft")
                .toolName("Create draft")
                .toolType("dsl_command")
                .sourceCode("pe:create_procurement_comparison_draft")
                .providerCode("dsl")
                .riskLevel("L2")
                .confirmationPolicy("confirm")
                .requiresConfirmation(true)
                .parameterSchema(Map.of(
                        "type", "object",
                        "properties", Map.of("productId", Map.of("type", "string"))))
                .build());

        AgentExecutionState first = factory.chatTurnState(
                ctx,
                "pcba_agent",
                "session-1",
                "openai",
                "gpt-test",
                0,
                "required",
                "system prompt containing private operating rules",
                4096,
                messages,
                llmTools,
                toolDefinitions,
                Map.of("toolId", "toolu-1", "toolName", "cmd_pe_create_procurement_comparison_draft"));
        AgentExecutionState second = factory.chatTurnState(
                ctx,
                "pcba_agent",
                "session-1",
                "openai",
                "gpt-test",
                0,
                "required",
                "system prompt containing private operating rules",
                4096,
                messages,
                llmTools,
                toolDefinitions,
                Map.of("toolName", "cmd_pe_create_procurement_comparison_draft", "toolId", "toolu-1"));

        assertThat(first.schemaVersion()).isEqualTo("agent-runtime-state/v1");
        assertThat(first.executionKind()).isEqualTo("chat_turn");
        assertThat(first.turnId()).isEqualTo(ctx.turnId());
        assertThat(first.tenantId()).isEqualTo(7L);
        assertThat(first.userId()).isEqualTo(100L);
        assertThat(first.agentCode()).isEqualTo("pcba_agent");
        assertThat(first.providerCode()).isEqualTo("openai");
        assertThat(first.model()).isEqualTo("gpt-test");
        assertThat(first.round()).isZero();
        assertThat(first.toolChoice()).isEqualTo("required");
        assertThat(first.stateHash()).hasSize(64).isEqualTo(second.stateHash());

        assertThat(first.context().systemPromptHash()).hasSize(64);
        assertThat(first.context().messagesHash()).hasSize(64);
        assertThat(first.context().toolsHash()).hasSize(64);
        assertThat(first.context().messageCount()).isEqualTo(2);
        assertThat(first.context().toolCount()).isEqualTo(1);

        assertThat(first.tools()).hasSize(1);
        AgentToolManifestItem tool = first.tools().get(0);
        assertThat(tool.toolCode()).isEqualTo("cmd_pe_create_procurement_comparison_draft");
        assertThat(tool.toolType()).isEqualTo("dsl_command");
        assertThat(tool.riskLevel()).isEqualTo("L2");
        assertThat(tool.requiresConfirmation()).isTrue();
        assertThat(tool.schemaHash()).hasSize(64);

        Map<String, Object> snapshot = first.toSnapshotMap();
        assertThat(snapshot).containsEntry("stateHash", first.stateHash());
        assertThat(snapshot.toString())
                .doesNotContain("private operating rules")
                .doesNotContain("please create a draft")
                .doesNotContain("apiKey")
                .doesNotContain("baseUrl");
    }

    @Test
    @DisplayName("handles null optional collections as empty manifests")
    void chatTurnStateHandlesNullCollections() {
        AgentExecutionState state = factory.chatTurnState(
                TurnContext.legacyDefault(1L, 2L, 2L),
                "aurabot",
                null,
                "stub",
                "stub-model",
                1,
                null,
                null,
                1024,
                null,
                null,
                null,
                null);

        assertThat(state.context().messageCount()).isZero();
        assertThat(state.context().toolCount()).isZero();
        assertThat(state.tools()).isEmpty();
        assertThat(state.pending()).isEmpty();
        assertThat(state.toSnapshotMap().toString()).doesNotContain("null=null");
    }

    @Test
    @DisplayName("drops sensitive pending keys from snapshots")
    void chatTurnStateDropsSensitivePendingKeys() {
        AgentExecutionState state = factory.chatTurnState(
                TurnContext.legacyDefault(1L, 2L, 2L),
                "aurabot",
                "session-1",
                "openai",
                "gpt-test",
                0,
                null,
                "system prompt",
                1024,
                List.of(LlmChatRequest.Message.text("user", "hello")),
                List.of(),
                List.of(),
                Map.of(
                        "toolId", "toolu-1",
                        "apiKey", "sk-secret",
                        "baseUrl", "https://llm.internal",
                        "previewToken", "preview-secret",
                        "nested", Map.of("apiKey", "nested-secret")));

        assertThat(state.pending()).containsEntry("toolId", "toolu-1");
        assertThat(state.pending()).containsKey("nestedHash");
        assertThat(state.pending()).doesNotContainKeys("apiKey", "baseUrl", "previewToken");
        assertThat(state.toSnapshotMap().toString())
                .doesNotContain("sk-secret")
                .doesNotContain("https://llm.internal")
                .doesNotContain("preview-secret")
                .doesNotContain("nested-secret")
                .doesNotContain("apiKey")
                .doesNotContain("baseUrl")
                .doesNotContain("previewToken");
    }

    @Test
    @DisplayName("builds a deterministic ACP run state from AgentToolDefinition snapshots")
    void acpRunStateBuildsDeterministicSnapshot() {
        List<AgentToolDefinition> tools = List.of(AgentToolDefinition.builder()
                .name("crm_customer_lookup")
                .description("Lookup customer records")
                .toolType("dsl_query")
                .sourceCode("crm.customer.lookup")
                .riskLevel("L0")
                .confirmationPolicy("none")
                .inputSchema(Map.of("type", "object", "properties", Map.of("keyword", Map.of("type", "string"))))
                .build());

        AgentExecutionState state = factory.acpRunState(
                7L,
                100L,
                "run-001",
                "task-001",
                "customer_agent",
                "anthropic",
                "claude-test",
                "system prompt with internal routing rules",
                "Find customers updated today",
                4096,
                tools,
                Map.of("toolDiscoveryMode", "bif"));

        assertThat(state.executionKind()).isEqualTo("acp_run");
        assertThat(state.turnId()).isNull();
        assertThat(state.runPid()).isEqualTo("run-001");
        assertThat(state.taskPid()).isEqualTo("task-001");
        assertThat(state.context().messageCount()).isEqualTo(1);
        assertThat(state.context().toolCount()).isEqualTo(1);
        assertThat(state.tools()).hasSize(1);
        assertThat(state.tools().get(0).toolCode()).isEqualTo("crm_customer_lookup");
        assertThat(state.tools().get(0).schemaHash()).hasSize(64);
        assertThat(state.pending()).containsEntry("toolDiscoveryMode", "bif");
        assertThat(state.stateHash()).hasSize(64);
        assertThat(state.toSnapshotMap().toString())
                .doesNotContain("internal routing rules")
                .doesNotContain("Find customers updated today")
                .doesNotContain("apiKey")
                .doesNotContain("baseUrl");
    }
}
