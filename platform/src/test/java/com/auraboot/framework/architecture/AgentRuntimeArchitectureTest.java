package com.auraboot.framework.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Agent runtime architecture constraints")
class AgentRuntimeArchitectureTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java/com/auraboot/framework");
    private static final Pattern GENERIC_EXECUTE_TOOL_METHOD =
            Pattern.compile("\\bexecuteTool\\s*\\(");
    private static final Pattern LOCAL_TOOL_LOOP_RESULT_PARSER =
            Pattern.compile("\\b(?:parseToolLoopResult|normalizeToolLoopResult)\\s*\\(");
    private static final Pattern LOCAL_AGENT_LLM_RESOLVER =
            Pattern.compile("\\b(?:resolveProviderCode|resolveModel)\\s*\\(\\s*Map<String, Object>\\s+agentDef");
    private static final Pattern LOCAL_LLM_MESSAGE_TAPE_HELPER =
            Pattern.compile("\\bprivate\\s+[^\\n]+\\s+(?:buildAssistantMessage|buildToolResultBlock|buildToolResultMessage|serializeMessages|deserializeMessages|extractTextFromResponse|sanitizeAssistantText)\\s*\\(");

    @Test
    @DisplayName("only ToolLoopService may call SkillToolExecutor dispatch or confirm")
    void onlyToolLoopServiceMayCallSkillToolExecutor() throws Exception {
        List<Path> offenders = productionJavaFiles()
                .filter(path -> !path.endsWith("agent/service/ToolLoopService.java"))
                .filter(path -> containsAny(path,
                        "skillToolExecutor.dispatch(",
                        "skillToolExecutor.confirm("))
                .toList();

        assertThat(offenders)
                .as("AuraBot skill execution must stay inside ToolLoopService")
                .isEmpty();
    }

    @Test
    @DisplayName("tool discovery and execution ports must not expose generic executeTool fallback")
    void portsDoNotExposeGenericExecuteToolFallback() throws Exception {
        List<Path> offenders = List.of(
                        MAIN_SOURCES.resolve("agent/port/ToolDiscoveryPort.java"))
                .stream()
                .filter(path -> contains(path, GENERIC_EXECUTE_TOOL_METHOD))
                .toList();

        assertThat(offenders)
                .as("Port contracts must not reintroduce a generic executeTool runtime path")
                .isEmpty();
    }

    @Test
    @DisplayName("legacy ToolExecutionPort must not be reintroduced")
    void toolExecutionPortIsRemoved() {
        assertThat(MAIN_SOURCES.resolve("agent/port/ToolExecutionPort.java"))
                .as("Tool execution must stay on ToolLoopService; do not reintroduce an execution port adapter")
                .doesNotExist();
    }

    @Test
    @DisplayName("entry adapters must not call package-local DSL shortcut execution")
    void entryAdaptersMustNotCallDslShortcutExecution() throws Exception {
        List<Path> offenders = productionJavaFiles()
                .filter(path -> !path.endsWith("agent/service/ToolLoopService.java"))
                .filter(path -> !path.endsWith("agent/service/SkillEngine.java"))
                .filter(path -> containsAny(path,
                        ".executeDslCommand(",
                        ".executeDslQuery(",
                        " executeDslCommand(",
                        " executeDslQuery("))
                .toList();

        assertThat(offenders)
                .as("Entry adapters must use executeToolCall/confirmAuraBotSkill so auth/effects/actions/contracts stay unified")
                .isEmpty();
    }

    @Test
    @DisplayName("agent runtime must not return deterministic fake tool execution stubs")
    void agentRuntimeDoesNotReturnFakeToolExecutionStubs() throws Exception {
        List<Path> offenders = productionJavaFiles()
                .filter(path -> containsAny(path, "Tool executed:", "Tool executed: "))
                .toList();

        assertThat(offenders)
                .as("Tool calls must execute through ToolLoopService, not return fake deterministic stubs")
                .isEmpty();
    }

    @Test
    @DisplayName("external side-effect tools must claim durable execution before provider dispatch")
    void externalSideEffectToolsClaimDurableExecutionBeforeProviderDispatch() throws Exception {
        Path toolLoop = MAIN_SOURCES.resolve("agent/service/ToolLoopService.java");
        String text = Files.readString(toolLoop);

        assertThat(text)
                .as("External write side effects need an execution ledger boundary before provider/API dispatch")
                .contains(
                        "private DurableToolExecutionLedger durableToolExecutionLedger;",
                        "requiresDurableExecutionBoundary(effects)",
                        "durableToolExecutionLedger.claim(durableExecutionRequest)",
                        "durableToolExecutionLedger.complete(",
                        "durableToolExecutionLedger.fail(",
                        "Durable tool execution ledger is not available. No external side effect was executed.",
                        "return emitDurableReplay(");
        assertThat(text.indexOf("durableToolExecutionLedger.claim(durableExecutionRequest)"))
                .as("Durable execution must be claimed before provider-backed tool dispatch")
                .isLessThan(text.indexOf("executeProviderTool(toolDef, input, tenantId)"));
        assertThat(text.indexOf("durableToolExecutionLedger.claim(durableExecutionRequest)"))
                .as("Durable execution must be claimed before legacy api_call dispatch")
                .isLessThan(text.indexOf("executeApiCall(toolDef.getSourceCode(), input)"));
    }

    @Test
    @DisplayName("chat path must not call removed discovery or execution executeTool methods")
    void chatPathDoesNotCallRemovedExecuteToolMethods() throws Exception {
        List<Path> offenders = productionJavaFiles()
                .filter(path -> containsAny(path,
                        "toolDiscoveryPort.executeTool(",
                        "ToolDiscoveryPort.executeTool(",
                        "ToolExecutionPort.executeTool("))
                .toList();

        assertThat(offenders)
                .as("Chat and agent paths must use ToolLoopService instead of removed executeTool shortcuts")
                .isEmpty();
    }

    @Test
    @DisplayName("tool loop result parsing must use the shared normalizer")
    void toolLoopResultParsingUsesSharedNormalizer() throws Exception {
        List<Path> offenders = productionJavaFiles()
                .filter(path -> !path.endsWith("agent/runtime/ToolLoopResultNormalizer.java"))
                .filter(path -> contains(path, LOCAL_TOOL_LOOP_RESULT_PARSER))
                .toList();

        assertThat(offenders)
                .as("Tool result parsing must stay centralized in ToolLoopResultNormalizer")
                .isEmpty();
    }

    @Test
    @DisplayName("agent definition LLM resolution must use the shared resolver")
    void agentDefinitionLlmResolutionUsesSharedResolver() throws Exception {
        List<Path> offenders = productionJavaFiles()
                .filter(path -> !path.endsWith("agent/runtime/LlmRuntimeResolver.java"))
                .filter(path -> contains(path, LOCAL_AGENT_LLM_RESOLVER))
                .toList();

        assertThat(offenders)
                .as("Agent definition provider/model resolution must stay centralized in LlmRuntimeResolver")
                .isEmpty();
    }

    @Test
    @DisplayName("conversation chokepoint must use PendingContinuationService for chat resume")
    void conversationChokepointUsesPendingContinuationServiceForChatResume() {
        Path chokepoint = MAIN_SOURCES.resolve("conversation/ConversationTurnServiceImpl.java");

        assertThat(containsAny(chokepoint, "resumeApprovedTurnFromPending("))
                .as("ConversationTurnServiceImpl must not call AuraBotChatService resume internals directly")
                .isFalse();
    }

    @Test
    @DisplayName("AuraBotChatService must not own pending continuation entrypoints")
    void auraBotChatServiceDoesNotOwnPendingContinuationEntrypoints() {
        Path auraBotChatService = MAIN_SOURCES.resolve("aurabot/service/AuraBotChatService.java");

        assertThat(containsAny(auraBotChatService, "resumeApprovedTurnFromPending("))
                .as("Pending continuation runtime must live in AuraBotPendingContinuationService")
                .isFalse();
    }

    @Test
    @DisplayName("pending continuation must not depend on AuraBotChatService internals")
    void pendingContinuationDoesNotDependOnAuraBotChatServiceInternals() {
        Path pendingContinuation = MAIN_SOURCES.resolve("aurabot/service/AuraBotPendingContinuationService.java");

        assertThat(containsAny(pendingContinuation, "AuraBotChatService."))
                .as("Pending continuation may share runtime helpers, but must not depend on the light chat service")
                .isFalse();
    }

    @Test
    @DisplayName("AuraBotChatService must not expose shared LLM runtime helpers")
    void auraBotChatServiceDoesNotExposeSharedLlmRuntimeHelpers() {
        Path auraBotChatService = MAIN_SOURCES.resolve("aurabot/service/AuraBotChatService.java");

        assertThat(containsAny(auraBotChatService,
                "static Map<String, Object> buildGenerationSpanInput(",
                "static Map<String, Object> buildGenerationSpanOutput(",
                "static boolean isToolOffered("))
                .as("Shared LLM runtime helpers must live in LlmChatRuntimeSupport")
                .isFalse();
    }

    @Test
    @DisplayName("AuraBot light chat must not own raw provider HTTP streaming")
    void auraBotLightChatDoesNotOwnRawProviderHttpStreaming() {
        Path auraBotChatService = MAIN_SOURCES.resolve("aurabot/service/AuraBotChatService.java");

        assertThat(containsAny(auraBotChatService,
                "HttpClient",
                "HttpRequest",
                "HttpResponse",
                "streamAnthropic(",
                "streamOpenAiCompatible(",
                "resolveApiFormat(",
                "listAllProviders()"))
                .as("AuraBot light chat must call providers through LlmProvider.streamChat instead of owning provider-specific HTTP clients")
                .isFalse();
    }

    @Test
    @DisplayName("AuraBot light chat provider streaming must live in ChatTurnRuntime")
    void auraBotLightChatProviderStreamingUsesChatTurnRuntime() {
        Path auraBotChatService = MAIN_SOURCES.resolve("aurabot/service/AuraBotChatService.java");

        assertThat(containsAny(auraBotChatService,
                "provider.streamChat(",
                "ReasoningTagSanitizer",
                "emitThinkingBlocks("))
                .as("AuraBotChatService should prepare context and delegate provider streaming to ChatTurnRuntime")
                .isFalse();
        assertThat(containsAny(auraBotChatService, "chatTurnRuntime.streamProviderResponse("))
                .as("AuraBotChatService should delegate light chat streaming to the shared chat runtime")
                .isTrue();
    }

    @Test
    @DisplayName("AuraBot light chat provider resolution must use shared LLM runtime resolver")
    void auraBotLightChatProviderResolutionUsesSharedRuntimeResolver() {
        Path auraBotChatService = MAIN_SOURCES.resolve("aurabot/service/AuraBotChatService.java");

        assertThat(containsAny(auraBotChatService,
                "String resolveProvider(Long tenantId, ChatRequest request)",
                ".resolveProviderByModel("))
                .as("AuraBot light chat must not own a second provider override/model-inference implementation")
                .isFalse();
        assertThat(containsAny(auraBotChatService, "LlmRuntimeResolver.resolveChatProviderCode("))
                .as("AuraBot light chat should use the shared provider/model resolver")
                .isTrue();
    }

    @Test
    @DisplayName("chat adapters must use shared LLM message tape helpers")
    void chatAdaptersUseSharedLlmMessageTapeHelpers() throws Exception {
        List<Path> offenders = productionJavaFiles()
                .filter(path -> !path.endsWith("agent/runtime/LlmMessageTapeSupport.java"))
                .filter(path -> contains(path, LOCAL_LLM_MESSAGE_TAPE_HELPER))
                .toList();

        assertThat(offenders)
                .as("Message tape construction, persistence, and final text extraction must stay centralized in LlmMessageTapeSupport")
                .isEmpty();
    }

    @Test
    @DisplayName("chat adapters must use shared text message construction")
    void chatAdaptersUseSharedTextMessageConstruction() throws Exception {
        List<Path> chatAdapterFiles = List.of(
                MAIN_SOURCES.resolve("agent/service/AgentChatPortImpl.java"),
                MAIN_SOURCES.resolve("aurabot/service/AuraBotChatService.java"));

        List<Path> offenders = chatAdapterFiles.stream()
                .filter(path -> containsAny(path,
                        "private List<LlmChatRequest.Message> buildProviderMessages(",
                        "messages.add(LlmChatRequest.Message.text(message.getRole()",
                        "messages.add(LlmChatRequest.Message.builder()\n                            .role(msg.getRole())"))
                .toList();

        assertThat(offenders)
                .as("Frontend history and current-user message construction must stay centralized in LlmMessageTapeSupport")
                .isEmpty();
    }

    @Test
    @DisplayName("durable runtime LLM responses must pass through the shared response guard")
    void durableRuntimeLlmResponsesUseSharedGuard() {
        List<Path> durableRuntimeFiles = List.of(
                MAIN_SOURCES.resolve("agent/service/StepLoopService.java"),
                MAIN_SOURCES.resolve("agent/service/AgentRunService.java"));

        List<Path> offenders = durableRuntimeFiles.stream()
                .filter(path -> containsAny(path,
                        "LlmChatResponse response = provider.chat(",
                        "LlmChatResponse resp = provider.chat("))
                .toList();

        assertThat(offenders)
                .as("Durable runtime loops must validate provider responses with LlmResponseGuard before reading content or token counters")
                .isEmpty();
    }

    @Test
    @DisplayName("chat adapters must call providers through ChatTurnRuntime")
    void chatAdaptersCallProvidersThroughChatTurnRuntime() {
        List<Path> chatAdapterFiles = List.of(
                MAIN_SOURCES.resolve("agent/service/AgentChatPortImpl.java"),
                MAIN_SOURCES.resolve("aurabot/service/AuraBotPendingContinuationService.java"));

        List<Path> offenders = chatAdapterFiles.stream()
                .filter(path -> containsAny(path,
                        "response = provider.chat(",
                        "LlmChatResponse response = provider.chat("))
                .toList();

        assertThat(offenders)
                .as("Named-agent chat and pending resume must call LLM providers through ChatTurnRuntime")
                .isEmpty();
    }

    @Test
    @DisplayName("chat adapters must not own private final-response streaming helpers")
    void chatAdaptersDoNotOwnFinalResponseStreamingHelpers() {
        List<Path> chatAdapterFiles = List.of(
                MAIN_SOURCES.resolve("agent/service/AgentChatPortImpl.java"),
                MAIN_SOURCES.resolve("aurabot/service/AuraBotPendingContinuationService.java"));

        List<Path> offenders = chatAdapterFiles.stream()
                .filter(path -> containsAny(path, "private TurnOutcome streamFinalResponse("))
                .toList();

        assertThat(offenders)
                .as("Final response text extraction and sink completion must live in ChatTurnRuntime")
                .isEmpty();
    }

    @Test
    @DisplayName("named-agent finalization must use ChatTurnRuntime tape finalization")
    void namedAgentFinalizationUsesChatTurnRuntimeTapeFinalization() {
        Path namedAgentAdapter = MAIN_SOURCES.resolve("agent/service/AgentChatPortImpl.java");

        assertThat(containsAny(namedAgentAdapter, "return chatTurnRuntime.streamFinalResponse(response, sink, null);"))
                .as("Named-agent final responses must append assistant tape and persist through ChatTurnRuntime.completeFinalResponse")
                .isFalse();
        assertThat(containsAny(namedAgentAdapter,
                "chatTurnRuntime.completeFinalResponse(",
                "chatTurnRuntime.runToolLoop("))
                .as("Named-agent final responses should go through the shared chat runtime finalization path")
                .isTrue();
    }

    @Test
    @DisplayName("chat adapters must use ChatTurnRuntime for tool-use tape rounds")
    void chatAdaptersUseChatTurnRuntimeForToolUseTapeRounds() {
        List<Path> chatAdapterFiles = List.of(
                MAIN_SOURCES.resolve("agent/service/AgentChatPortImpl.java"),
                MAIN_SOURCES.resolve("aurabot/service/AuraBotPendingContinuationService.java"));

        List<Path> offenders = chatAdapterFiles.stream()
                .filter(path -> containsAny(path,
                        "LlmMessageTapeSupport.buildAssistantMessage(response.getContent())",
                        "LlmMessageTapeSupport.buildToolResultMessage(toolResultBlocks)"))
                .toList();

        assertThat(offenders)
                .as("Tool-use assistant and tool-result tape mutations must use ChatTurnRuntime")
                .isEmpty();
        Path namedAgentAdapter = MAIN_SOURCES.resolve("agent/service/AgentChatPortImpl.java");
        assertThat(containsAny(namedAgentAdapter, "chatTurnRuntime.runToolLoop("))
                .as("Named-agent chat should delegate the full tool-loop control flow to ChatTurnRuntime")
                .isTrue();

        Path pendingContinuation = MAIN_SOURCES.resolve("aurabot/service/AuraBotPendingContinuationService.java");
        assertThat(containsAny(pendingContinuation, "chatTurnRuntime.runToolLoop("))
                .as(pendingContinuation + " should delegate resumed tool-loop control flow to ChatTurnRuntime")
                .isTrue();
    }

    @Test
    @DisplayName("named-agent adapter must not own chat tool-loop control flow")
    void namedAgentAdapterDoesNotOwnChatToolLoopControlFlow() {
        Path namedAgentAdapter = MAIN_SOURCES.resolve("agent/service/AgentChatPortImpl.java");

        assertThat(containsAny(namedAgentAdapter,
                "for (int round = 0; round <",
                "Agent tool loop exceeded maximum rounds"))
                .as("AgentChatPortImpl should be a named-agent adapter; ChatTurnRuntime owns chat loop control flow")
                .isFalse();
    }

    @Test
    @DisplayName("pending continuation must not own chat tool-loop control flow")
    void pendingContinuationDoesNotOwnChatToolLoopControlFlow() {
        Path pendingContinuation = MAIN_SOURCES.resolve("aurabot/service/AuraBotPendingContinuationService.java");

        assertThat(containsAny(pendingContinuation,
                "for (int round = 0; round <",
                "for (int round = 0; round <=",
                "Tool loop exceeded maximum rounds"))
                .as("Pending continuation should resume through ChatTurnRuntime instead of owning a private round loop")
                .isFalse();
    }

    @Test
    @DisplayName("chat adapters must use PendingToolSnapshotFactory for pending snapshots")
    void chatAdaptersUsePendingToolSnapshotFactory() {
        List<Path> chatAdapterFiles = List.of(
                MAIN_SOURCES.resolve("agent/service/AgentChatPortImpl.java"),
                MAIN_SOURCES.resolve("aurabot/service/AuraBotPendingContinuationService.java"));

        List<Path> offenders = chatAdapterFiles.stream()
                .filter(path -> containsAny(path, "PendingToolSnapshot.builder()"))
                .toList();

        assertThat(offenders)
                .as("Pending snapshot construction must stay centralized in PendingToolSnapshotFactory")
                .isEmpty();
        for (Path adapter : chatAdapterFiles) {
            assertThat(containsAny(adapter, "pendingToolSnapshotFactory."))
                    .as(adapter + " should use the shared pending snapshot factory")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("agent runtime must own pending snapshot DTOs without depending on AuraBot storage")
    void agentRuntimeDoesNotDependOnAuraBotStorageDtos() throws Exception {
        List<Path> offenders = Files.walk(MAIN_SOURCES.resolve("agent/runtime"))
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> containsAny(path,
                        "com.auraboot.framework.aurabot.service.ChatSessionStore",
                        "ChatSessionStore."))
                .toList();

        assertThat(offenders)
                .as("agent.runtime must define runtime DTOs and must not depend on AuraBot storage internals")
                .isEmpty();
    }

    @Test
    @DisplayName("pending tool flow must use PendingToolStore instead of ChatSessionStore")
    void pendingToolFlowUsesPendingToolStoreBoundary() {
        Path conversationTurnService = MAIN_SOURCES.resolve("conversation/ConversationTurnServiceImpl.java");
        Path namedAgentChat = MAIN_SOURCES.resolve("agent/service/AgentChatPortImpl.java");
        Path pendingContinuation = MAIN_SOURCES.resolve("aurabot/service/AuraBotPendingContinuationService.java");
        Path chatSessionStore = MAIN_SOURCES.resolve("aurabot/service/ChatSessionStore.java");

        assertThat(containsAny(conversationTurnService, "ChatSessionStore"))
                .as("Conversation chokepoint should consume pending tools through PendingToolStore")
                .isFalse();
        assertThat(containsAny(conversationTurnService, "PendingToolStore"))
                .as("Conversation chokepoint should depend on the pending store boundary")
                .isTrue();

        assertThat(containsAny(namedAgentChat,
                "chatSessionStore.storePending(",
                "chatSessionStore.consumePending("))
                .as("Named-agent chat may use ChatSessionStore for message tape, but pending calls must use PendingToolStore")
                .isFalse();
        assertThat(containsAny(namedAgentChat, "PendingToolStore"))
                .as("Named-agent chat should use the pending store boundary for pending tool payloads")
                .isTrue();

        assertThat(containsAny(pendingContinuation, "ChatSessionStore"))
                .as("Pending continuation should depend on PendingToolStore, not AuraBot session storage")
                .isFalse();
        assertThat(containsAny(pendingContinuation, "PendingToolStore"))
                .as("Pending continuation should use the pending store boundary")
                .isTrue();
        assertThat(containsAny(pendingContinuation, ".executeConfirmed("))
                .as("Pending continuation must fail closed without an AgentToolDefinition snapshot instead of using ChatToolExecutor.executeConfirmed")
                .isFalse();

        assertThat(containsAny(chatSessionStore, "implements PendingToolStore"))
                .as("ChatSessionStore remains the storage adapter, but through the PendingToolStore contract")
                .isTrue();
    }

    @Test
    @DisplayName("runtime callers must use owner-aware pending consume")
    void runtimeCallersUseOwnerAwarePendingConsume() throws Exception {
        List<Path> offenders = productionJavaFiles()
                .filter(path -> !path.endsWith("aurabot/service/ChatSessionStore.java"))
                .filter(path -> containsAny(path, ".consumePending("))
                .toList();

        assertThat(offenders)
                .as("Runtime callers must not delete pending payloads before owner validation")
                .isEmpty();
    }

    @Test
    @DisplayName("PendingToolStore must not expose owner-blind consume API")
    void pendingToolStoreDoesNotExposeOwnerBlindConsume() throws Exception {
        Path pendingToolStore = MAIN_SOURCES.resolve("agent/runtime/PendingToolStore.java");

        assertThat(Files.readString(pendingToolStore))
                .as("Pending consume must validate owner identity before delete")
                .doesNotContain("consumePending(String");
    }

    @Test
    @DisplayName("named-agent chat must use ChatMessageTapeStore instead of ChatSessionStore")
    void namedAgentChatUsesChatMessageTapeStoreBoundary() {
        Path namedAgentChat = MAIN_SOURCES.resolve("agent/service/AgentChatPortImpl.java");
        Path chatSessionStore = MAIN_SOURCES.resolve("aurabot/service/ChatSessionStore.java");

        assertThat(containsAny(namedAgentChat, "ChatSessionStore"))
                .as("Named-agent chat should not depend on AuraBot session storage for message tape")
                .isFalse();
        assertThat(containsAny(namedAgentChat, "ChatMessageTapeStore"))
                .as("Named-agent chat should use the message tape store boundary")
                .isTrue();
        assertThat(containsAny(chatSessionStore, "implements PendingToolStore, ChatMessageTapeStore"))
                .as("ChatSessionStore remains the backing adapter behind runtime storage contracts")
                .isTrue();
    }

    @Test
    @DisplayName("production code must not name ChatSessionStore outside the storage adapter")
    void productionCodeDoesNotNameChatSessionStoreOutsideStorageAdapter() throws Exception {
        List<Path> offenders = productionJavaFiles()
                .filter(path -> !path.endsWith("aurabot/service/ChatSessionStore.java"))
                .filter(path -> containsAny(path, "ChatSessionStore"))
                .toList();

        assertThat(offenders)
                .as("Consumers should speak in runtime storage contracts, not the legacy concrete adapter name")
                .isEmpty();
    }

    @Test
    @DisplayName("named-agent chat must require PendingToolStore without tape-store fallback")
    void namedAgentChatRequiresPendingToolStoreWithoutTapeStoreFallback() {
        Path namedAgentChat = MAIN_SOURCES.resolve("agent/service/AgentChatPortImpl.java");

        assertThat(containsAny(namedAgentChat,
                "@Autowired(required = false)\n    private PendingToolStore pendingToolStore",
                "chatMessageTapeStore instanceof PendingToolStore",
                "private PendingToolStore pendingToolStore()"))
                .as("Pending tool storage must be an explicit dependency, not inferred from the tape store")
                .isFalse();
        assertThat(containsAny(namedAgentChat, "private final PendingToolStore pendingToolStore;"))
                .as("Named-agent chat should receive the pending store as a required constructor dependency")
                .isTrue();
    }

    @Test
    @DisplayName("chat runtimes must require core collaborators through constructors")
    void chatRuntimesRequireCoreCollaboratorsThroughConstructors() throws Exception {
        Path namedAgentChat = MAIN_SOURCES.resolve("agent/service/AgentChatPortImpl.java");
        Path pendingContinuation = MAIN_SOURCES.resolve("aurabot/service/AuraBotPendingContinuationService.java");

        String namedText = Files.readString(namedAgentChat);
        assertThat(namedText)
                .as("AgentChatPortImpl must not hide missing runtime beans behind optional field injection")
                .doesNotContain(
                        "@Autowired(required = false)\n    private ToolLoopService toolLoopService",
                        "@Autowired(required = false)\n    private AgentRuntimeStateFactory runtimeStateFactory",
                        "@Autowired(required = false)\n    private AgentReducer agentReducer",
                        "@Autowired(required = false)\n    private ChatTurnRuntime chatTurnRuntime",
                        "@Autowired(required = false)\n    private PendingToolSnapshotFactory pendingToolSnapshotFactory",
                        "new AgentRuntimeStateFactory()",
                        "new DefaultAgentReducer()",
                        "new ChatTurnRuntime()",
                        "new PendingToolSnapshotFactory()");
        assertThat(namedText)
                .contains(
                        "private final ToolLoopService toolLoopService;",
                        "private final AgentRuntimeStateFactory runtimeStateFactory;",
                        "private final AgentReducer agentReducer;",
                        "private final ChatTurnRuntime chatTurnRuntime;",
                        "private final PendingToolSnapshotFactory pendingToolSnapshotFactory;");

        String continuationText = Files.readString(pendingContinuation);
        assertThat(continuationText)
                .as("AuraBotPendingContinuationService must require the same runtime collaborators")
                .doesNotContain(
                        "@Autowired(required = false)\n    private ToolLoopService toolLoopService",
                        "@Autowired(required = false)\n    private ChatTurnRuntime chatTurnRuntime",
                        "@Autowired(required = false)\n    private PendingToolSnapshotFactory pendingToolSnapshotFactory",
                        "new ChatTurnRuntime()",
                        "new PendingToolSnapshotFactory()");
        assertThat(continuationText)
                .contains(
                        "private final ToolLoopService toolLoopService;",
                        "private final ChatTurnRuntime chatTurnRuntime;",
                        "private final PendingToolSnapshotFactory pendingToolSnapshotFactory;");
    }

    @Test
    @DisplayName("durable run provider chain must use shared runtime resolver")
    void durableRunProviderChainUsesSharedRuntimeResolver() throws Exception {
        Path agentRunService = MAIN_SOURCES.resolve("agent/service/AgentRunService.java");
        String text = Files.readString(agentRunService);

        assertThat(text)
                .as("AgentRunService must not own provider fallback parsing")
                .doesNotContain("private List<String> buildProviderChain(")
                .contains("LlmRuntimeResolver.resolveAgentProviderChain(");
        assertThat(Files.readString(MAIN_SOURCES.resolve("agent/runtime/LlmRuntimeResolver.java")))
                .contains("resolveAgentProviderChain(");
    }

    @Test
    @DisplayName("durable run orchestrator must not own step-loop provider calls")
    void durableRunOrchestratorDoesNotOwnStepLoopProviderCalls() throws Exception {
        Path agentRunService = MAIN_SOURCES.resolve("agent/service/AgentRunService.java");
        String text = Files.readString(agentRunService);

        assertThat(text)
                .as("AgentRunService should orchestrate run/task lifecycle and delegate LLM step loops to PlanService or StepLoopService")
                .doesNotContain(
                        "private boolean attemptReplan(",
                        "provider.chat(",
                        "LlmChatRequest.builder()");
    }

    @Test
    @DisplayName("conversation chokepoint must delegate durable execution to DurableWorkflowEngine")
    void conversationChokepointDelegatesDurableExecutionToDurableWorkflowEngine() throws Exception {
        Path chokepoint = MAIN_SOURCES.resolve("conversation/ConversationTurnServiceImpl.java");
        String text = Files.readString(chokepoint);

        assertThat(text)
                .as("ConversationTurnService should choose lifecycle route but not own durable run/task execution")
                .contains("private DurableWorkflowEngine durableWorkflowEngine;")
                .contains("durableWorkflowEngine.startConversationRun(")
                .contains("durableWorkflowEngine.resumeConversationRun(")
                .doesNotContain(
                        "agentRunService.executeTaskSync(",
                        "private String createAcpTaskRow(",
                        "private TurnOutcome mapRunToTurnOutcome(");
        assertThat(MAIN_SOURCES.resolve("agent/runtime/DurableWorkflowEngine.java"))
                .exists();
    }

    @Test
    @DisplayName("durable run orchestrator must require runtime state factory")
    void durableRunOrchestratorRequiresRuntimeStateFactory() throws Exception {
        Path agentRunService = MAIN_SOURCES.resolve("agent/service/AgentRunService.java");
        String text = Files.readString(agentRunService);

        assertThat(text)
                .as("AgentRunService must not silently instantiate runtime state collaborators")
                .doesNotContain(
                        "@org.springframework.beans.factory.annotation.Autowired(required = false)\n    private AgentRuntimeStateFactory runtimeStateFactory",
                        "new AgentRuntimeStateFactory()")
                .contains("private final AgentRuntimeStateFactory runtimeStateFactory;");
    }

    @Test
    @DisplayName("durable plan persistence must append workflow checkpoints")
    void durablePlanPersistenceAppendsWorkflowCheckpoints() throws Exception {
        String stepLoop = Files.readString(MAIN_SOURCES.resolve("agent/service/StepLoopService.java"));
        String schema = Files.readString(Path.of("src/main/resources/database/schema.sql"));

        assertThat(stepLoop)
                .as("ACP plan persistence must keep append-only checkpoint history, not only overwrite current_step")
                .contains(
                        "private final DurableWorkflowCheckpointStore checkpointStore;",
                        "checkpointStore.recordPlanCheckpoint(");
        assertThat(schema)
                .as("The checkpoint history table must exist in the reset schema")
                .contains("CREATE TABLE IF NOT EXISTS ab_agent_run_checkpoint");
    }

    @Test
    @DisplayName("pending snapshot factory must require runtime state factory")
    void pendingSnapshotFactoryRequiresRuntimeStateFactory() throws Exception {
        Path snapshotFactory = MAIN_SOURCES.resolve("agent/runtime/PendingToolSnapshotFactory.java");
        String text = Files.readString(snapshotFactory);

        assertThat(text)
                .as("PendingToolSnapshotFactory must not silently instantiate runtime state collaborators")
                .doesNotContain(
                        "public PendingToolSnapshotFactory()",
                        "new AgentRuntimeStateFactory()")
                .contains("private final AgentRuntimeStateFactory runtimeStateFactory;");
    }

    @Test
    @DisplayName("named-agent chat must resolve profile through AgentProfileResolver")
    void namedAgentChatResolvesProfileThroughAgentProfileResolver() throws Exception {
        Path namedAgentChat = MAIN_SOURCES.resolve("agent/service/AgentChatPortImpl.java");
        String text = Files.readString(namedAgentChat);

        assertThat(text)
                .as("AgentChatPortImpl should consume a resolved AgentProfile instead of parsing guardrails inline")
                .contains("AgentProfile profile = agentProfileResolver.resolve(")
                .doesNotContain("AgentProfilePermissionExtractor.extract(");
    }

    @Test
    @DisplayName("chat runtime must derive tenant envelope after catalog ACL filtering")
    void chatRuntimeDerivesTenantEnvelopeAfterCatalogAclFiltering() throws Exception {
        String runtime = Files.readString(MAIN_SOURCES.resolve("agent/runtime/ChatTurnRuntime.java"));
        String planner = Files.readString(MAIN_SOURCES.resolve("agent/runtime/policy/ExecutionEnvelopePlanner.java"));

        assertThat(runtime)
                .as("Catalog ACL must feed the tenant policy source before ExecutionEnvelopePlanner runs")
                .contains(
                        "filterCatalogAllowedToolDefinitions(",
                        "tenantPolicyFromCatalog(catalogAllowedDefinitions)",
                        "AgentTenantPolicy.fromCatalog(",
                        "new ExecutionEnvelopePlanner.Request(");
        assertThat(runtime.indexOf("filterCatalogAllowedToolDefinitions("))
                .isLessThan(runtime.indexOf("new ExecutionEnvelopePlanner.Request("));
        assertThat(planner)
                .as("ExecutionEnvelopePlanner must consume tenant policy as a first-class envelope source")
                .contains(
                        "AgentTenantPolicy tenantPolicy",
                        "applyPolicyBounds(",
                        "tenantPolicy.capabilityCeiling()",
                        "tenantPolicy.toolExposure()",
                        "tenantPolicy.durabilityPreference()");
    }

    @Test
    @DisplayName("AuraBot prompt context must use provenance-labeled context assembler")
    void aurabotPromptContextUsesProvenanceLabeledContextAssembler() throws Exception {
        String aurabot = Files.readString(MAIN_SOURCES.resolve("aurabot/service/AuraBotChatService.java"));
        String assembler = Files.readString(MAIN_SOURCES.resolve("agent/runtime/context/AgentContextAssembler.java"));
        String provenance = Files.readString(MAIN_SOURCES.resolve("agent/runtime/context/AgentContextProvenance.java"));

        assertThat(aurabot)
                .as("AuraBot must not keep adding unlabeled page/RAG prompt strings")
                .contains(
                        "AgentContextBundle contextBundle = new AgentContextAssembler(objectMapper).assemble(",
                        "contextBundle.renderPromptSection()");
        assertThat(assembler)
                .as("ContextAssembler must produce provenance labels that policy/evals can inspect")
                .contains(
                        "AgentContextProvenance",
                        "AgentContextSource.PAGE",
                        "AgentContextSource.SCHEMA",
                        "AgentContextSource.RECORD",
                        "AgentContextSource.RAG",
                        "AgentContextSensitivity.CONFIDENTIAL");
        assertThat(provenance)
                .contains(
                        "freshness",
                        "permission",
                        "recordIds");
    }

    private static Stream<Path> productionJavaFiles() throws IOException {
        return Files.walk(MAIN_SOURCES)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(AgentRuntimeArchitectureTest::isAgentRuntimePath);
    }

    private static boolean isAgentRuntimePath(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.contains("/agent/")
                || normalized.contains("/aurabot/")
                || normalized.contains("/conversation/");
    }

    private static boolean containsAny(Path path, String... needles) {
        try {
            String text = Files.readString(path);
            for (String needle : needles) {
                if (text.contains(needle)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    private static boolean contains(Path path, Pattern pattern) {
        try {
            return pattern.matcher(Files.readString(path)).find();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
