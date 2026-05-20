package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.LlmChunk;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.runtime.context.AgentContextBlock;
import com.auraboot.framework.agent.runtime.context.AgentContextProvenance;
import com.auraboot.framework.agent.runtime.context.AgentContextSensitivity;
import com.auraboot.framework.agent.runtime.context.AgentContextSource;
import com.auraboot.framework.agent.runtime.policy.ExecutionEnvelope;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ChatTurnRuntime")
class ChatTurnRuntimeTest {

    private final ChatTurnRuntime runtime = new ChatTurnRuntime();

    @Test
    @DisplayName("callProvider rejects empty provider responses before callers read tokens or content")
    void callProvider_emptyResponse_throwsSharedGuardFailure() throws Exception {
        LlmProvider provider = mock(LlmProvider.class);
        LlmChatRequest request = LlmChatRequest.builder().model("test-model").build();
        when(provider.chat(any(LlmChatRequest.class), eq("sk-test"), eq("https://llm.test")))
                .thenReturn(null);

        assertThatThrownBy(() -> runtime.callProvider(
                provider, request, "sk-test", "https://llm.test", "named-agent chat"))
                .isInstanceOf(LlmResponseGuard.EmptyLlmResponseException.class)
                .hasMessage("Empty response from LLM during named-agent chat");
    }

    @Test
    @DisplayName("callProvider returns valid responses and passes transport config through")
    void callProvider_validResponse_returnsProviderResponse() throws Exception {
        LlmProvider provider = mock(LlmProvider.class);
        LlmChatRequest request = LlmChatRequest.builder().model("test-model").build();
        LlmChatResponse response = response("hello");
        when(provider.chat(any(LlmChatRequest.class), eq("sk-test"), eq("https://llm.test")))
                .thenReturn(response);

        assertThat(runtime.callProvider(provider, request, "sk-test", "https://llm.test", "named-agent chat"))
                .isSameAs(response);
        verify(provider).chat(request, "sk-test", "https://llm.test");
    }

    @Test
    @DisplayName("streamFinalResponse emits sanitized text and done once")
    void streamFinalResponse_validResponse_emitsTextAndDone() {
        RecordingSink sink = new RecordingSink();
        LlmChatResponse response = response("<think>private</think> visible answer");

        TurnOutcome outcome = runtime.streamFinalResponse(response, sink, "trace-1");

        assertThat(sink.textChunks).containsExactly("visible answer");
        assertThat(sink.donePayloads).containsExactly("visible answer");
        assertThat(sink.doneTraceIds).containsExactly("trace-1");
        assertThat(outcome)
                .isEqualTo(new TurnOutcome.Success("visible answer", Map.of()));
    }

    @Test
    @DisplayName("streamProviderResponse filters reasoning tags and emits aggregate metadata")
    void streamProviderResponse_filtersReasoningTagsAndEmitsAggregateMetadata() throws Exception {
        LlmProvider provider = mock(LlmProvider.class);
        LlmChatRequest request = LlmChatRequest.builder().model("test-model").build();
        RecordingSink sink = new RecordingSink();
        LlmChatResponse aggregate = LlmChatResponse.builder()
                .stopReason("end_turn")
                .warnings(List.of("max_tokens adjusted"))
                .content(List.of(
                        LlmChatResponse.ContentBlock.builder()
                                .type("thinking")
                                .thinking("private reasoning")
                                .signature("sig-1")
                                .build(),
                        LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("Visible <think>hidden</think> answer")
                                .build()))
                .build();
        when(provider.streamChat(request, "sk-test", "https://llm.test"))
                .thenReturn(Flux.just(
                        LlmChunk.delta(0, "Visible <thi"),
                        LlmChunk.delta(1, "nk>hidden</thi"),
                        LlmChunk.delta(2, "nk> answer"),
                        LlmChunk.done(3, aggregate)));

        TurnOutcome outcome = runtime.streamProviderResponse(
                provider, request, "sk-test", "https://llm.test", sink, "trace-3");

        assertThat(sink.textChunks).containsExactly("Visible ", " answer");
        assertThat(sink.donePayloads).containsExactly("Visible  answer");
        assertThat(sink.doneTraceIds).containsExactly("trace-3");
        assertThat(sink.warnings).containsExactly("max_tokens adjusted");
        assertThat(sink.thinkingContents).containsExactly("private reasoning");
        assertThat(sink.thinkingSignatures).containsExactly("sig-1");
        assertThat(outcome).isEqualTo(new TurnOutcome.Success("Visible  answer", Map.of()));
        verify(provider).streamChat(request, "sk-test", "https://llm.test");
    }

    @Test
    @DisplayName("completeFinalResponse appends assistant tape before persistence and streaming")
    void completeFinalResponse_appendsAssistantTapeBeforePersistingAndStreaming() {
        RecordingSink sink = new RecordingSink();
        LlmChatResponse response = response("final answer");
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        messages.add(LlmChatRequest.Message.builder()
                .role("user")
                .content("question")
                .build());
        List<List<LlmChatRequest.Message>> persisted = new ArrayList<>();

        TurnOutcome outcome = runtime.completeFinalResponse(response, messages, persisted::add, sink, "trace-2");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getRole()).isEqualTo("assistant");
        assertThat(messages.get(1).getContent()).isInstanceOf(List.class);
        assertThat(persisted).containsExactly(messages);
        assertThat(sink.textChunks).containsExactly("final answer");
        assertThat(sink.donePayloads).containsExactly("final answer");
        assertThat(sink.doneTraceIds).containsExactly("trace-2");
        assertThat(outcome).isEqualTo(new TurnOutcome.Success("final answer", Map.of()));
    }

    @Test
    @DisplayName("recordToolUseResponse appends assistant tool-use tape")
    void recordToolUseResponse_appendsAssistantToolUseTape() {
        LlmChatResponse response = toolUseResponse();
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        messages.add(LlmChatRequest.Message.builder().role("user").content("run tool").build());

        runtime.recordToolUseResponse(response, messages);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getRole()).isEqualTo("assistant");
        assertThat(messages.get(1).getContent()).isInstanceOf(List.class);
    }

    @Test
    @DisplayName("completeToolResultRound appends tool-result tape before persistence")
    void completeToolResultRound_appendsToolResultTapeBeforePersistence() {
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        messages.add(LlmChatRequest.Message.builder().role("user").content("run tool").build());
        LlmChatRequest.ContentBlock toolResult = new LlmChatRequest.ContentBlock();
        toolResult.setType("tool_result");
        toolResult.setToolUseId("tool-1");
        toolResult.setResult("{\"success\":true}");
        List<List<LlmChatRequest.Message>> persisted = new ArrayList<>();

        runtime.completeToolResultRound(List.of(toolResult), messages, persisted::add);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getRole()).isEqualTo("user");
        assertThat(messages.get(1).getContent()).isEqualTo(List.of(toolResult));
        assertThat(persisted).containsExactly(messages);
    }

    @Test
    @DisplayName("runToolLoop denies write tools before execution inside a read-only envelope")
    void runToolLoop_readOnlyEnvelopeDeniesWriteToolBeforeExecution() throws Exception {
        LlmProvider provider = mock(LlmProvider.class);
        RecordingSink sink = new RecordingSink();
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        messages.add(LlmChatRequest.Message.builder().role("user").content("delete customer C-1").build());
        LlmChatRequest.Tool writeTool = LlmChatRequest.Tool.builder()
                .name("cmd:crm_customer_delete")
                .description("Delete customer")
                .inputSchema(Map.of("type", "object"))
                .build();
        ToolDefinition writeDefinition = ToolDefinition.builder()
                .toolCode("cmd:crm_customer_delete")
                .toolName("Delete customer")
                .toolType("dsl_command")
                .riskLevel("L2")
                .requiresApproval(false)
                .requiresConfirmation(false)
                .build();
        LlmChatResponse toolUse = LlmChatResponse.builder()
                .stopReason("tool_use")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("tool_use")
                        .id("tool-1")
                        .name("cmd:crm_customer_delete")
                        .input(Map.of("pid", "C-1"))
                        .build()))
                .build();
        LlmChatResponse finalResponse = response("I cannot delete records in read-only mode.");
        when(provider.chat(any(LlmChatRequest.class), eq("sk-test"), eq("https://llm.test")))
                .thenReturn(toolUse)
                .thenReturn(finalResponse);
        PolicyCallbacks callbacks = new PolicyCallbacks(ExecutionEnvelope.readOnlyCatalog());

        TurnOutcome outcome = runtime.runToolLoop(
                new ChatTurnRuntime.ChatToolLoopSpec(
                        new TurnContext("turn-1", 1L, 2L, null, null, "aurabot",
                                null, null, null, null, java.util.Set.of(), null, null, Instant.now()),
                        "aurabot",
                        provider,
                        "test",
                        "sk-test",
                        "https://llm.test",
                        "policy test",
                        "test-model",
                        "system",
                        256,
                        messages,
                        List.of(writeTool),
                        List.of(writeDefinition),
                        null,
                        null,
                        "session-1",
                        sink,
                        false,
                        false,
                        2,
                        null,
                        "trace-1",
                        null),
                callbacks);

        assertThat(callbacks.executeCalls).isZero();
        assertThat(sink.toolResults).hasSize(1);
        assertThat(sink.toolResults.get(0)).containsEntry("success", false);
        assertThat(String.valueOf(sink.toolResults.get(0).get("error")))
                .contains("not available in the current execution envelope");
        assertThat(outcome).isEqualTo(new TurnOutcome.Success(
                "I cannot delete records in read-only mode.", Map.of()));
    }

    @Test
    @DisplayName("runToolLoop filters write tools out of the pre-model catalog inside read-only envelope")
    void runToolLoop_filtersWriteToolsBeforeModelCall() throws Exception {
        LlmProvider provider = mock(LlmProvider.class);
        RecordingSink sink = new RecordingSink();
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        messages.add(LlmChatRequest.Message.builder().role("user").content("summarize customers").build());
        LlmChatRequest.Tool readTool = LlmChatRequest.Tool.builder()
                .name("nq:crm_customer_stats")
                .description("Customer stats")
                .inputSchema(Map.of("type", "object"))
                .build();
        LlmChatRequest.Tool writeTool = LlmChatRequest.Tool.builder()
                .name("cmd:crm_customer_delete")
                .description("Delete customer")
                .inputSchema(Map.of("type", "object"))
                .build();
        ToolDefinition readDefinition = ToolDefinition.builder()
                .toolCode("nq:crm_customer_stats")
                .toolType("dsl_query")
                .riskLevel("L0")
                .build();
        ToolDefinition writeDefinition = ToolDefinition.builder()
                .toolCode("cmd:crm_customer_delete")
                .toolType("dsl_command")
                .riskLevel("L2")
                .build();
        when(provider.chat(any(LlmChatRequest.class), eq("sk-test"), eq("https://llm.test")))
                .thenReturn(response("Customer stats are available."));
        PolicyCallbacks callbacks = new PolicyCallbacks(ExecutionEnvelope.readOnlyCatalog());

        runtime.runToolLoop(
                new ChatTurnRuntime.ChatToolLoopSpec(
                        new TurnContext("turn-2", 1L, 2L, null, null, "aurabot",
                                null, null, null, null, java.util.Set.of(), null, null, Instant.now()),
                        "aurabot",
                        provider,
                        "test",
                        "sk-test",
                        "https://llm.test",
                        "catalog policy test",
                        "test-model",
                        "system",
                        256,
                        messages,
                        List.of(readTool, writeTool),
                        List.of(readDefinition, writeDefinition),
                        null,
                        null,
                        "session-2",
                        sink,
                        false,
                        false,
                        1,
                        null,
                        "trace-2",
                        null),
                callbacks);

        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider).chat(requestCaptor.capture(), eq("sk-test"), eq("https://llm.test"));
        assertThat(requestCaptor.getValue().getTools())
                .extracting(LlmChatRequest.Tool::getName)
                .containsExactly("nq:crm_customer_stats");
    }

    @Test
    @DisplayName("runToolLoop applies caller catalog ACL before exposing tools to the model")
    void runToolLoop_appliesCallerCatalogAclBeforeModelCall() throws Exception {
        LlmProvider provider = mock(LlmProvider.class);
        RecordingSink sink = new RecordingSink();
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        messages.add(LlmChatRequest.Message.builder().role("user").content("summarize and update customers").build());
        LlmChatRequest.Tool readTool = LlmChatRequest.Tool.builder()
                .name("nq:crm_customer_stats")
                .description("Customer stats")
                .inputSchema(Map.of("type", "object"))
                .build();
        LlmChatRequest.Tool writeTool = LlmChatRequest.Tool.builder()
                .name("cmd:crm_customer_update")
                .description("Update customer")
                .inputSchema(Map.of("type", "object"))
                .build();
        ToolDefinition readDefinition = ToolDefinition.builder()
                .toolCode("nq:crm_customer_stats")
                .toolType("dsl_query")
                .riskLevel("L0")
                .build();
        ToolDefinition writeDefinition = ToolDefinition.builder()
                .toolCode("cmd:crm_customer_update")
                .toolType("dsl_command")
                .riskLevel("L2")
                .build();
        when(provider.chat(any(LlmChatRequest.class), eq("sk-test"), eq("https://llm.test")))
                .thenReturn(response("Customer stats are available."));
        PolicyCallbacks callbacks = new PolicyCallbacks(ExecutionEnvelope.writeCatalogWithGate()) {
            @Override
            public boolean allowToolInCatalog(ChatTurnRuntime.ChatToolLoopRound round, ToolDefinition definition) {
                return !"cmd:crm_customer_update".equals(definition.getToolCode());
            }
        };

        runtime.runToolLoop(
                new ChatTurnRuntime.ChatToolLoopSpec(
                        new TurnContext("turn-acl", 1L, 2L, null, null, "aurabot",
                                null, null, null, null, java.util.Set.of(), null, null, Instant.now()),
                        "aurabot",
                        provider,
                        "test",
                        "sk-test",
                        "https://llm.test",
                        "catalog acl test",
                        "test-model",
                        "system",
                        256,
                        messages,
                        List.of(readTool, writeTool),
                        List.of(readDefinition, writeDefinition),
                        java.util.Set.of("crm.customer.read", "crm.customer.update"),
                        null,
                        "session-acl",
                        sink,
                        false,
                        false,
                        1,
                        null,
                        "trace-acl",
                        null),
                callbacks);

        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider).chat(requestCaptor.capture(), eq("sk-test"), eq("https://llm.test"));
        assertThat(requestCaptor.getValue().getTools())
                .extracting(LlmChatRequest.Tool::getName)
                .containsExactly("nq:crm_customer_stats");
    }

    @Test
    @DisplayName("runToolLoop denies hallucinated write calls after catalog ACL hides write tools")
    void runToolLoop_deniesHiddenWriteToolCallAfterCatalogAcl() throws Exception {
        LlmProvider provider = mock(LlmProvider.class);
        RecordingSink sink = new RecordingSink();
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        messages.add(LlmChatRequest.Message.builder().role("user").content("summarize then delete customer").build());
        LlmChatRequest.Tool readTool = LlmChatRequest.Tool.builder()
                .name("nq:crm_customer_stats")
                .description("Customer stats")
                .inputSchema(Map.of("type", "object"))
                .build();
        LlmChatRequest.Tool writeTool = LlmChatRequest.Tool.builder()
                .name("cmd:crm_customer_delete")
                .description("Delete customer")
                .inputSchema(Map.of("type", "object"))
                .build();
        ToolDefinition readDefinition = ToolDefinition.builder()
                .toolCode("nq:crm_customer_stats")
                .toolType("dsl_query")
                .riskLevel("L0")
                .build();
        ToolDefinition writeDefinition = ToolDefinition.builder()
                .toolCode("cmd:crm_customer_delete")
                .toolType("dsl_command")
                .riskLevel("L2")
                .requiresConfirmation(true)
                .build();
        when(provider.chat(any(LlmChatRequest.class), eq("sk-test"), eq("https://llm.test")))
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("tool_use")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id("tool-hidden-write")
                                .name("cmd:crm_customer_delete")
                                .input(Map.of("pid", "C-1"))
                                .build()))
                        .build())
                .thenReturn(response("Write tools are not available in this turn."));
        PolicyCallbacks callbacks = new PolicyCallbacks(null) {
            @Override
            public boolean allowToolInCatalog(ChatTurnRuntime.ChatToolLoopRound round, ToolDefinition definition) {
                return !"cmd:crm_customer_delete".equals(definition.getToolCode());
            }
        };

        TurnOutcome outcome = runtime.runToolLoop(
                new ChatTurnRuntime.ChatToolLoopSpec(
                        new TurnContext("turn-acl-hidden-write", 1L, 2L, null, null, "aurabot",
                                null, null, null, null, java.util.Set.of(), null, null, Instant.now()),
                        "aurabot",
                        provider,
                        "test",
                        "sk-test",
                        "https://llm.test",
                        "catalog acl hidden write test",
                        "test-model",
                        "system",
                        256,
                        messages,
                        List.of(readTool, writeTool),
                        List.of(readDefinition, writeDefinition),
                        java.util.Set.of("crm.customer.read", "crm.customer.delete"),
                        null,
                        "session-acl-hidden-write",
                        sink,
                        false,
                        false,
                        2,
                        null,
                        "trace-acl-hidden-write",
                        null),
                callbacks);

        assertThat(callbacks.executeCalls).isZero();
        assertThat(callbacks.confirmationPendings).isEmpty();
        assertThat(sink.toolResults).hasSize(1);
        assertThat(sink.toolResults.get(0))
                .containsEntry("success", false)
                .containsEntry("reasonCode", "capability_ceiling_exceeded");
        assertThat(outcome).isEqualTo(new TurnOutcome.Success(
                "Write tools are not available in this turn.", Map.of()));
        verify(provider, times(2)).chat(any(LlmChatRequest.class), eq("sk-test"), eq("https://llm.test"));
    }

    @Test
    @DisplayName("runToolLoop passes policy decision fields into pending confirmation snapshot context")
    void runToolLoop_passesPolicyDecisionFieldsIntoPendingContext() throws Exception {
        LlmProvider provider = mock(LlmProvider.class);
        RecordingSink sink = new RecordingSink();
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        messages.add(LlmChatRequest.Message.builder().role("user").content("create draft").build());
        LlmChatRequest.Tool writeTool = LlmChatRequest.Tool.builder()
                .name("cmd:create_draft")
                .description("Create draft")
                .inputSchema(Map.of("type", "object"))
                .build();
        ToolDefinition writeDefinition = ToolDefinition.builder()
                .toolCode("cmd:create_draft")
                .toolType("dsl_command")
                .riskLevel("L2")
                .parameterSchema(Map.of("type", "object"))
                .build();
        when(provider.chat(any(LlmChatRequest.class), eq("sk-test"), eq("https://llm.test")))
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("tool_use")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id("tool-write")
                                .name("cmd:create_draft")
                                .input(Map.of("productId", "P-100"))
                                .build()))
                        .build());
        List<AgentContextBlock> contextBlocks = List.of(new AgentContextBlock(
                "Current Record Data",
                "{}",
                new AgentContextProvenance(
                        AgentContextSource.RECORD,
                        "crm_customer/C-100",
                        "CLIENT_SNAPSHOT",
                        "PAGE_CONTEXT",
                        AgentContextSensitivity.CONFIDENTIAL,
                        List.of("C-100"),
                        1L,
                        "web",
                        true)));
        PolicyCallbacks callbacks = new PolicyCallbacks(ExecutionEnvelope.writeCatalogWithGate(), contextBlocks);

        TurnOutcome outcome = runtime.runToolLoop(
                new ChatTurnRuntime.ChatToolLoopSpec(
                        new TurnContext("turn-3", 1L, 2L, null, null, "aurabot",
                                null, null, null, null, java.util.Set.of(), null, null, Instant.now()),
                        "aurabot",
                        provider,
                        "test",
                        "sk-test",
                        "https://llm.test",
                        "pending policy test",
                        "test-model",
                        "system",
                        256,
                        messages,
                        List.of(writeTool),
                        List.of(writeDefinition),
                        null,
                        null,
                        "session-3",
                        sink,
                        false,
                        false,
                        1,
                        null,
                        "trace-3",
                        null),
                callbacks);

        assertThat(outcome).isInstanceOf(TurnOutcome.PendingConfirmation.class);
        assertThat(callbacks.confirmationPendings).hasSize(1);
        ChatTurnRuntime.PendingChatTool pending = callbacks.confirmationPendings.get(0);
        assertThat(pending.toolVersion()).isEqualTo("v1");
        assertThat(pending.argsHash()).hasSize(64);
        assertThat(pending.idempotencyKey()).isEqualTo("cmd:create_draft:v1:" + pending.argsHash());
        assertThat(pending.policyDecisionReason()).isEqualTo("user_confirmation_required");
        assertThat(pending.preview()).isEqualTo("Execute cmd:create_draft with 1 argument(s).");
        assertThat(pending.expiresAt()).isAfter(Instant.now().minusSeconds(1));
        assertThat(pending.contextBlocks()).containsExactlyElementsOf(contextBlocks);
    }

    private LlmChatResponse response(String text) {
        return LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text")
                        .text(text)
                        .build()))
                .inputTokens(10)
                .outputTokens(5)
                .build();
    }

    private LlmChatResponse toolUseResponse() {
        return LlmChatResponse.builder()
                .stopReason("tool_use")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("tool_use")
                        .id("tool-1")
                        .name("lookup")
                        .input(Map.of("id", "A-1"))
                        .build()))
                .build();
    }

    private static final class RecordingSink implements ResponseSink {
        private final List<String> textChunks = new ArrayList<>();
        private final List<String> donePayloads = new ArrayList<>();
        private final List<String> doneTraceIds = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> thinkingContents = new ArrayList<>();
        private final List<String> thinkingSignatures = new ArrayList<>();
        private final List<Map<String, Object>> toolResults = new ArrayList<>();

        @Override
        public void onTextChunk(String text) {
            textChunks.add(text);
        }

        @Override
        public void onToolStart(String toolId, String toolName, Map<String, Object> input) {
        }

        @Override
        public void onToolResult(String toolId, Map<String, Object> result, boolean success) {
            toolResults.add(result);
        }

        @Override
        public void onConfirmRequired(String toolId, String toolName, String description,
                                      Map<String, Object> input, String pendingTurnId) {
        }

        @Override
        public void onError(String message, String traceId) {
        }

        @Override
        public void onDone(String finalResponse, String traceId) {
            donePayloads.add(finalResponse);
            doneTraceIds.add(traceId);
        }

        @Override
        public void onThinking(String content, int tokens, String signature) {
            thinkingContents.add(content);
            thinkingSignatures.add(signature);
        }

        @Override
        public void onWarnings(List<String> warnings) {
            this.warnings.addAll(warnings);
        }
    }

    private static class PolicyCallbacks implements ChatTurnRuntime.ChatToolLoopCallbacks {
        private final ExecutionEnvelope envelope;
        private final List<AgentContextBlock> contextBlocks;
        private int executeCalls;
        private final List<ChatTurnRuntime.PendingChatTool> confirmationPendings = new ArrayList<>();

        private PolicyCallbacks(ExecutionEnvelope envelope) {
            this(envelope, List.of());
        }

        private PolicyCallbacks(ExecutionEnvelope envelope, List<AgentContextBlock> contextBlocks) {
            this.envelope = envelope;
            this.contextBlocks = contextBlocks;
        }

        @Override
        public AgentExecutionState buildRoundState(ChatTurnRuntime.ChatToolLoopRound round) {
            return null;
        }

        @Override
        public AgentExecutionState reduce(AgentExecutionState state, AgentRuntimeEvent event) {
            return state;
        }

        @Override
        public ExecutionEnvelope executionEnvelope(ChatTurnRuntime.ChatToolLoopRound round) {
            return envelope;
        }

        @Override
        public List<AgentContextBlock> contextBlocks(ChatTurnRuntime.ChatToolLoopRound round) {
            return contextBlocks;
        }

        @Override
        public Map<String, Object> executeTool(ChatTurnRuntime.ChatToolCall call) {
            executeCalls++;
            return Map.of("success", true);
        }

        @Override
        public void storeConfirmationPending(ChatTurnRuntime.PendingChatTool pending) {
            confirmationPendings.add(pending);
        }

        @Override
        public void storeApprovalPending(ChatTurnRuntime.PendingChatTool pending, Map<String, Object> result) {
        }

        @Override
        public void persistMessages(String sessionId, List<LlmChatRequest.Message> messages) {
        }

        @Override
        public TurnOutcome buildApprovalRequiredOutcome(Map<String, Object> result, String toolName,
                                                        Map<String, Object> input, ResponseSink sink) {
            return new TurnOutcome.PendingConfirmation("approval-1", "", "approval-1");
        }

        @Override
        public TurnOutcome buildHandoffOutcome(LlmChatResponse response, ResponseSink sink, Map<String, Object> input) {
            return new TurnOutcome.Success("", Map.of());
        }

        @Override
        public String buildToolDescription(String toolName, Map<String, Object> input) {
            return toolName;
        }
    }
}
