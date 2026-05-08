package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.skill.PreviewTokenStore;
import com.auraboot.framework.aurabot.skill.SkillRequest;
import com.auraboot.framework.aurabot.skill.provider.SkillToolExecutor;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.conversation.ConversationTurnService;
import com.auraboot.framework.conversation.ConversationTurnService.ConfirmDecision;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Plan §C-5 Task 6 — chat-skill end-to-end IT.
 *
 * <p>Drives the {@link ConversationTurnService#resumeTurn} chokepoint with a
 * pre-seeded {@link ChatSessionStore.PendingTool} carrying the
 * {@code _aurabot_skill=true} extension marker added in T5. This bypasses the
 * SSE-streaming first leg (which would require a real LLM tool_use round) and
 * exercises only the resume branch — the unit under test for T6.
 *
 * <p>4 cases (Spec §6.2):
 * <ol>
 *   <li>{@code lowSkill_endToEnd} — LOW skill via {@code aurabot:} prefix
 *       returns inline {@code {success:true, data:...}}, no PendingTool.</li>
 *   <li>{@code highSkill_happyPath} — HIGH skill suspends → resume(APPROVED)
 *       executes {@code SkillToolExecutor.confirm} and persists ab_meta_model
 *       row.</li>
 *   <li>{@code highSkill_cancel} — resume(CANCELLED) short-circuits before
 *       touching the skill: ab_meta_model row never created.</li>
 *   <li>{@code expiredToken} — resume(APPROVED) with a forcibly-evicted
 *       token: tool_result envelope flips to {@code success=false} with
 *       {@code PREVIEW_TOKEN_INVALID} message; no DDL fires.</li>
 * </ol>
 *
 * <p>Strategy B (per plan §Task 6 Step 4): we drive {@code resumeTurn}
 * directly rather than going through {@code POST /chat/stream}. The first-leg
 * suspension is covered by unit tests
 * ({@link ChatToolExecutorAuraBotBranchTest}); T6's contract is the resume
 * pipeline. A {@link MockBean LlmProviderFactory} returns a stub provider so
 * the post-confirm continuation loop terminates with {@code end_turn} on the
 * very first call — we don't need a real LLM to verify the chokepoint.
 *
 * <p>Real PG (port 25442) + real Redis (port 26389) via the
 * {@code skills-c2-test} profile (same as T3 + T2). Permissions are mocked
 * via {@link UserPermissionService} / {@link PermissionMapper} — mirrors the
 * existing skill ITs.
 */
@ActiveProfiles({"integration-test", "skills-c2-test"})
class AuraBotChatSkillResumeIntegrationTest extends BaseIntegrationTest {

    // Resume-path collaborators
    @Autowired ConversationTurnService turnService;
    @Autowired ChatSessionStore chatSessionStore;
    @Autowired SkillToolExecutor skillToolExecutor;
    @Autowired ChatToolExecutor chatToolExecutor;
    @Autowired ObjectMapper objectMapper;

    // Real-DB probes
    @Autowired MetaModelService metaModelService;
    @Autowired DynamicDataMapper dynamicDataMapper;
    @Autowired PreviewTokenStore previewTokenStore;
    // PreviewTokenStore key prefix (constant) — used by force-eviction test
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    // Stubs
    @MockBean LlmProviderFactory llmProviderFactory;
    @MockBean UserPermissionService userPermissionService;
    @MockBean PermissionMapper permissionMapper;

    private final Set<String> currentPermissions = new HashSet<>();
    private String testModelCode;
    private long tenantId;
    private long userId;

    @BeforeEach
    void setUp() {
        currentPermissions.clear();
        tenantId = getTestTenant().getId();
        userId = getTestUser().getId();
        MetaContext.setContext(tenantId, userId, null, "it-c5-t6-user");

        when(userPermissionService.getUserPermissionIds(eq(userId)))
                .thenAnswer(inv -> Set.of(1L));
        when(permissionMapper.findByIds(any())).thenAnswer(inv ->
                currentPermissions.stream().map(code -> {
                    Permission p = new Permission();
                    p.setCode(code);
                    return p;
                }).toList());

        // Stub provider returns end_turn on the very first call so the resume
        // loop's continuation finishes immediately after consuming our seeded
        // tool_result.
        LlmProvider stubProvider = new EndTurnStubProvider();
        when(llmProviderFactory.getProvider(anyString())).thenReturn(stubProvider);

        testModelCode = "it_c5t6_" + UniqueIdGenerator.generate().toLowerCase().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        try {
            MetaModelDTO m = metaModelService.findByCode(testModelCode);
            if (m != null) {
                metaModelService.delete(m.getPid());
            }
        } catch (ValidationException ignored) {
            // model never created — happy path
        } catch (RuntimeException ignored) {
            // tearDown must not mask test assertion failures
        }
        try {
            dynamicDataMapper.alterTable("DROP TABLE IF EXISTS mt_" + testModelCode);
        } catch (RuntimeException ignored) {
            // best-effort
        }
        MetaContext.clear();
    }

    // ─── Case 1: LOW skill end-to-end via ChatToolExecutor "aurabot:" branch ───
    @Test
    @DisplayName("LOW skill: aurabot:model:query executes inline through chat tool executor")
    void lowSkill_endToEnd_inlineExecute() {
        currentPermissions.add("MODEL.READ");

        // T4 wired aurabot: prefix → SkillToolExecutor.dispatch. LOW returns
        // EXECUTED, ChatToolExecutor packages as {success:true, data:...}.
        // No suspend, no PendingTool — that's the contract.
        Map<String, Object> input = Map.of("keyword", "no-such-model-xyz");
        Map<String, Object> envelope = chatToolExecutor.execute(
                "aurabot:model:query", input, null);

        assertThat(envelope)
                .as("LOW skill must execute inline; success envelope")
                .containsEntry("success", true)
                .containsKey("data")
                .doesNotContainKey("_aurabot_skill_pending");
    }

    // ─── Case 2: HIGH skill happy path — resume(APPROVED) confirms + writes ───
    @Test
    @DisplayName("HIGH skill happy path: resume(APPROVED) calls confirm() and persists ab_meta_model")
    void highSkill_happyPath_confirmAndPersist() {
        currentPermissions.add("MODEL.CREATE");

        // 1) Suspend leg (simulate what T5 wiring does in the chat loop):
        //    dispatch the HIGH skill to mint a real preview token, then seed a
        //    PendingTool carrying the _aurabot_skill marker.
        ObjectNode params = objectMapper.createObjectNode()
                .put("code", testModelCode)
                .put("displayName", "C5T6 Happy Path Model");
        SkillRequest req = SkillRequest.builder()
                .skillName("model:create")
                .params(params)
                .build();
        SkillToolExecutor.DispatchOutcome pending = skillToolExecutor.dispatch("model:create", req);
        assertThat(pending.kind()).isEqualTo(SkillToolExecutor.OutcomeKind.PREVIEW_PENDING);
        String previewToken = pending.previewToken();
        assertThat(previewToken).isNotBlank();

        // Reuse the SAME params node we hashed at dispatch — funnel it through
        // convertValue so the seeded PendingTool.input is the exact JSON shape
        // the resume path will hash on confirm. Avoids any insertion-order or
        // type-coercion drift between the ObjectNode and a hand-rolled Map.of().
        Map<String, Object> input = objectMapper.convertValue(params,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

        String turnId = "turn-c5t6-happy-" + UniqueIdGenerator.generate();
        String toolId = "tool-" + UniqueIdGenerator.generate();
        seedPendingSkillTool(turnId, toolId, "model:create",
                input, previewToken, "high", previewPayloadOf(pending));

        // 2) Resume(APPROVED) — chokepoint dispatches to
        //    AuraBotChatService.resumeApprovedTurnFromPending → executeResumeTool
        //    → SkillToolExecutor.confirm with our token.
        CapturingSink sink = new CapturingSink();
        TurnOutcome outcome = turnService.resumeTurn(turnId, ConfirmDecision.APPROVED, sink);

        // 3) Assert turn finished cleanly. The stub provider returns end_turn
        //    so resume completes with Success.
        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);

        // 4) Sink saw a tool_result with success=true (the confirm envelope).
        assertThat(sink.toolResults).as("tool_result emitted").isNotEmpty();
        ToolResultEvent skillResult = sink.toolResults.stream()
                .filter(r -> toolId.equals(r.toolId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected tool_result for skill toolId=" + toolId
                                + ", got: " + sink.toolResults));
        assertThat(skillResult.success()).as("skill confirm succeeded").isTrue();
        assertThat(skillResult.result()).containsKey("data");

        // 5) Real DB row exists — execute() actually ran on confirm.
        // Re-establish MetaContext: tenant interceptor may have cleared it
        // during commit (mirrors SkillToolExecutorIntegrationTest pattern).
        MetaContext.setContext(tenantId, userId, null, "it-c5-t6-user");
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                "SELECT pid FROM ab_meta_model WHERE code = #{params.c}",
                Map.of("c", testModelCode));
        assertThat(rows)
                .as("ab_meta_model row must be persisted by skill.execute on confirm")
                .hasSize(1);
    }

    // ─── Case 3: HIGH skill cancel — resume(CANCELLED) skips skill entirely ───
    @Test
    @DisplayName("HIGH skill cancel: resume(CANCELLED) interrupts before confirm; no DDL")
    void highSkill_cancel_doesNotPersist() {
        currentPermissions.add("MODEL.CREATE");

        ObjectNode params = objectMapper.createObjectNode()
                .put("code", testModelCode)
                .put("displayName", "C5T6 Cancel Model");
        SkillRequest req = SkillRequest.builder()
                .skillName("model:create")
                .params(params)
                .build();
        SkillToolExecutor.DispatchOutcome pending = skillToolExecutor.dispatch("model:create", req);
        String previewToken = pending.previewToken();

        Map<String, Object> input = objectMapper.convertValue(params,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        String turnId = "turn-c5t6-cancel-" + UniqueIdGenerator.generate();
        String toolId = "tool-" + UniqueIdGenerator.generate();
        seedPendingSkillTool(turnId, toolId, "model:create",
                input, previewToken, "high", previewPayloadOf(pending));

        CapturingSink sink = new CapturingSink();
        TurnOutcome outcome = turnService.resumeTurn(turnId, ConfirmDecision.CANCELLED, sink);

        // resumeTurn short-circuits CANCELLED into TurnOutcome.Interrupted —
        // chat impl never sees the pending entry, executeResumeTool never fires.
        assertThat(outcome).isInstanceOf(TurnOutcome.Interrupted.class);
        TurnOutcome.Interrupted interrupted = (TurnOutcome.Interrupted) outcome;
        assertThat(interrupted.reason()).isEqualTo("user_cancelled");

        // No ab_meta_model row.
        MetaContext.setContext(tenantId, userId, null, "it-c5-t6-user");
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                "SELECT pid FROM ab_meta_model WHERE code = #{params.c}",
                Map.of("c", testModelCode));
        assertThat(rows).as("CANCELLED resume must not persist anything").isEmpty();
    }

    // ─── Case 4: expired token — confirm() throws PREVIEW_TOKEN_INVALID ───────
    @Test
    @DisplayName("Expired token: resume(APPROVED) surfaces success=false envelope; no persist")
    void expiredToken_resumeYieldsErrorEnvelope() {
        currentPermissions.add("MODEL.CREATE");

        ObjectNode params = objectMapper.createObjectNode()
                .put("code", testModelCode)
                .put("displayName", "C5T6 Expired Model");
        SkillRequest req = SkillRequest.builder()
                .skillName("model:create")
                .params(params)
                .build();
        SkillToolExecutor.DispatchOutcome pending = skillToolExecutor.dispatch("model:create", req);
        String previewToken = pending.previewToken();

        Map<String, Object> input = objectMapper.convertValue(params,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        // Force the token to "expire" by deleting it directly from Redis.
        // PreviewTokenStore.consume() will return Optional.empty(), which the
        // validator surfaces as SkillSpiException(PREVIEW_TOKEN_INVALID),
        // which executeResumeTool catches and converts to {success:false,...}.
        redisTemplate.delete(PreviewTokenStore.KEY_PREFIX + previewToken);

        String turnId = "turn-c5t6-expired-" + UniqueIdGenerator.generate();
        String toolId = "tool-" + UniqueIdGenerator.generate();
        seedPendingSkillTool(turnId, toolId, "model:create",
                input, previewToken, "high", previewPayloadOf(pending));

        CapturingSink sink = new CapturingSink();
        TurnOutcome outcome = turnService.resumeTurn(turnId, ConfirmDecision.APPROVED, sink);

        // The continuation loop still runs (the stub LLM returns end_turn
        // immediately), so the turn ends as Success — but the tool_result
        // for our skill carries success=false with the typed error.
        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);

        ToolResultEvent skillResult = sink.toolResults.stream()
                .filter(r -> toolId.equals(r.toolId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected tool_result for skill toolId=" + toolId
                                + ", got: " + sink.toolResults));
        assertThat(skillResult.success())
                .as("expired token must surface success=false on tool_result")
                .isFalse();
        assertThat(String.valueOf(skillResult.result().get("error")))
                .as("error envelope must mention PREVIEW_TOKEN_INVALID or token-related cause")
                .containsIgnoringCase("token");

        // No DDL fired.
        MetaContext.setContext(tenantId, userId, null, "it-c5-t6-user");
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                "SELECT pid FROM ab_meta_model WHERE code = #{params.c}",
                Map.of("c", testModelCode));
        assertThat(rows).as("expired-token resume must not persist anything").isEmpty();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> previewPayloadOf(SkillToolExecutor.DispatchOutcome outcome) {
        if (outcome == null || outcome.preview() == null
                || outcome.preview().getPayload() == null) {
            return null;
        }
        Object payload = outcome.preview().getPayload();
        if (payload instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of("payload", payload);
    }

    private void seedPendingSkillTool(String turnId, String toolId, String toolName,
                                       Map<String, Object> input, String previewToken,
                                       String riskLevel, Map<String, Object> preview) {
        Map<String, Object> extension = new java.util.LinkedHashMap<>();
        extension.put("_aurabot_skill", true);
        extension.put("previewToken", previewToken);
        extension.put("riskLevel", riskLevel);
        if (preview != null) {
            extension.put("preview", preview);
        }

        ChatSessionStore.PendingTool pendingTool = ChatSessionStore.PendingTool.builder()
                .turnId(turnId)
                .tenantId(tenantId)
                .userId(userId)
                .humanMemberId(getTestTenantMember().getId())
                .conversationId(null)
                .agentCode("aurabot")
                .sessionId("c5t6-session-" + turnId)
                .toolId(toolId)
                .toolName(toolName)
                .input(input)
                .description("C-5 T6 skill confirm test")
                .modelCode(null)
                .messages(seedConversationMessages(toolId, toolName, input))
                .providerCode("anthropic")
                .apiKey("stub-key")
                .baseUrl("https://stub")
                .model("stub-model")
                .systemPrompt("stub")
                .maxTokens(1024)
                .currentLoop(0)
                .extension(extension)
                .build();

        chatSessionStore.storePending(turnId, pendingTool);
    }

    /**
     * Seed a minimal Anthropic-shaped message list so {@code deserializeMessages}
     * has something to work with. The resume path appends a tool_result block
     * onto this and re-invokes the (stub) LLM.
     */
    private List<Map<String, Object>> seedConversationMessages(
            String toolId, String toolName, Map<String, Object> input) {
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(Map.of(
                "role", "user",
                "content", List.of(Map.of("type", "text", "text", "create the model"))));
        msgs.add(Map.of(
                "role", "assistant",
                "content", List.of(Map.of(
                        "type", "tool_use",
                        "id", toolId,
                        "name", toolName,
                        "input", input))));
        return msgs;
    }

    /**
     * Stub provider: always returns {@code end_turn} with empty content so the
     * post-confirm continuation loop in {@code doResumeApprovedInner}
     * terminates on its first iteration. Lets us assert resume behaviour
     * without standing up a real Anthropic / OpenAI client.
     */
    private static final class EndTurnStubProvider implements LlmProvider {
        @Override public String getProviderCode() { return "anthropic"; }
        @Override public String getDisplayName() { return "C5T6 End-Turn Stub"; }
        @Override public boolean supportsTools() { return true; }
        @Override public LlmChatResponse chat(LlmChatRequest request, String apiKey, String baseUrl) {
            LlmChatResponse.ContentBlock textBlock = LlmChatResponse.ContentBlock.builder()
                    .type("text")
                    .text("ok")
                    .build();
            return LlmChatResponse.builder()
                    .stopReason("end_turn")
                    .content(List.of(textBlock))
                    .inputTokens(1)
                    .outputTokens(1)
                    .build();
        }
        @Override public double estimateCost(String model, int inputTokens, int outputTokens) {
            return 0.0;
        }
        @Override public String getDefaultBaseUrl() { return "https://stub"; }
        @Override public String getDefaultModel() { return "stub-model"; }
    }

    /** Captures the few sink events we assert on. */
    private static final class CapturingSink implements ResponseSink {
        final List<ToolResultEvent> toolResults = new ArrayList<>();
        final AtomicReference<String> doneText = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();

        @Override public void onTextChunk(String text) {}
        @Override public void onToolStart(String toolId, String toolName, Map<String, Object> input) {}
        @Override public void onToolResult(String toolId, Map<String, Object> result, boolean success) {
            toolResults.add(new ToolResultEvent(toolId, result, success));
        }
        @Override public void onConfirmRequired(String toolId, String toolName, String description,
                                                  Map<String, Object> input, String pendingTurnId) {}
        @Override public void onError(String message, String traceId) { error.set(message); }
        @Override public void onDone(String finalResponse, String traceId) { doneText.set(finalResponse); }
    }

    private record ToolResultEvent(String toolId, Map<String, Object> result, boolean success) {}
}
