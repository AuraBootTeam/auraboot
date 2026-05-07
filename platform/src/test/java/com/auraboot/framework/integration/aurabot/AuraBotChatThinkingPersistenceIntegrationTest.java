package com.auraboot.framework.integration.aurabot;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.dto.AuraBotConversationItem;
import com.auraboot.framework.aurabot.dto.AuraBotConversationMessage;
import com.auraboot.framework.aurabot.service.AuraBotConversationService;
import com.auraboot.framework.conversation.AuraBotTurnPersistence;
import com.auraboot.framework.conversation.InboundMode;
import com.auraboot.framework.conversation.TurnArtifacts;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.conversation.TurnRequest;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D.1 (ACP backlog 2026-05-07) integration test — Anthropic Extended Thinking
 * persistence on the {@code ab_im_message} agent row.
 *
 * <p>Drives {@link AuraBotTurnPersistence#persistOutbound(TurnContext,
 * TurnOutcome, TurnArtifacts)} directly with three shapes:
 * <ul>
 *   <li>Case A — turn produced thinking: assert
 *       {@code thinking_content} carries the joined prose and
 *       {@code thinking_signature} is non-null.</li>
 *   <li>Case B — turn produced no thinking ({@link TurnArtifacts#EMPTY}):
 *       assert both columns are NULL (not empty string — see schema doc
 *       red line "no empty-string poison").</li>
 *   <li>Case C — load via the message-history endpoint
 *       ({@link AuraBotConversationService#getMessages}): assert the
 *       returned {@link AuraBotConversationMessage} surfaces
 *       {@code thinkingContent} so the frontend can re-render the
 *       reasoning pane on history reload.</li>
 * </ul>
 *
 * <p>Uses real PostgreSQL via {@link BaseIntegrationTest}. We do NOT mock
 * {@code LlmProvider} or stand up an in-process Anthropic SSE server here:
 * the persistence path itself is the unit under test, and feeding it through
 * a real Anthropic stream would conflate D.1's contract with the streaming
 * contract that's already covered by
 * {@code AuraBotChatServiceThinkingIntegrationTest}.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
@DisplayName("AuraBotTurnPersistence — D.1 thinking persistence on ab_im_message")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuraBotChatThinkingPersistenceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AuraBotTurnPersistence persistence;

    @Autowired
    private ImMessageMapper messageMapper;

    @Autowired
    private AuraBotConversationService conversationService;

    private Long conversationId;
    private Long tenantId;
    private Long humanMemberId;

    @BeforeEach
    void setUp() {
        tenantId = getTestTenant().getId();
        humanMemberId = getTestTenantMember().getId();
        Long userId = getTestUser().getId();
        String userPid = getTestUser().getPid();
        String username = getTestUser().getUserName();
        MetaContext.setContext(tenantId, userId, userPid, username);
        MetaContext.setMemberId(humanMemberId);

        AuraBotConversationItem conv = conversationService.ensureConversation(tenantId, humanMemberId, "aurabot");
        conversationId = conv.getConversationId();
    }

    @AfterEach
    void clear() {
        MetaContext.clear();
    }

    private TurnContext newCtx() {
        return new TurnContext(
                com.auraboot.framework.common.util.UniqueIdGenerator.generate(),
                tenantId,
                getTestUser().getId(),
                humanMemberId,
                null,
                null,
                null,
                conversationId,
                null,
                null,
                null,
                null,
                Instant.now());
    }

    /**
     * Seed an inbound user row first so the conversation history endpoint has
     * something to anchor on. Mirrors the live runTurn path which calls
     * {@code persistInbound} before {@code persistOutbound}.
     */
    private void seedInboundUserRow(String text) {
        TurnRequest req = newTurnRequest(text, "in-" + System.nanoTime());
        persistence.persistInbound(req, null);
    }

    private TurnRequest newTurnRequest(String message, String clientMsgId) {
        com.auraboot.framework.aurabot.dto.ChatRequest legacy =
                new com.auraboot.framework.aurabot.dto.ChatRequest();
        legacy.setMessage(message);
        legacy.setSessionId("d1-thinking-persistence");
        legacy.setAgentCode("aurabot");
        legacy.setConversationId(conversationId);
        legacy.setClientMsgId(clientMsgId);
        return new TurnRequest(
                tenantId,
                getTestUser().getId(),
                humanMemberId,
                "web",
                "aurabot",
                conversationId,
                clientMsgId,
                message,
                null, null,
                InboundMode.NEW_FROM_REQUEST,
                null,
                null,
                null,
                null,
                legacy);
    }

    // =========================================================================
    // Case A — turn with thinking → thinking_content + thinking_signature persisted
    // =========================================================================

    @Test
    @DisplayName("Case A: persistOutbound w/ TurnArtifacts(thinking, sig) -> ab_im_message row carries both")
    void persistOutbound_withThinkingArtifacts_writesThinkingColumns() {
        TurnContext ctx = newCtx();
        TurnOutcome.Success success = new TurnOutcome.Success(
                "Final answer: 42.", java.util.Map.of());
        TurnArtifacts artifacts = TurnArtifacts.of(
                "Step 1: parse the question.\n\nStep 2: compute the answer.",
                "sig-anthropic-XYZ");

        Long messageId = persistence.persistOutbound(ctx, success, artifacts);

        assertThat(messageId).as("agent row id").isNotNull();
        ImMessage saved = messageMapper.selectById(messageId);
        assertThat(saved).isNotNull();
        assertThat(saved.getSenderType()).isEqualTo("agent");
        assertThat(saved.getMessageType()).isEqualTo("ai_response");
        assertThat(saved.getContent()).isEqualTo("Final answer: 42.");
        assertThat(saved.getThinkingContent())
                .as("D.1: persisted reasoning prose preserved verbatim")
                .isEqualTo("Step 1: parse the question.\n\nStep 2: compute the answer.");
        assertThat(saved.getThinkingSignature())
                .as("D.1: opaque verification signature persisted")
                .isEqualTo("sig-anthropic-XYZ");
    }

    // =========================================================================
    // Case B — turn without thinking → both columns NULL (no empty-string poison)
    // =========================================================================

    @Test
    @DisplayName("Case B: persistOutbound w/ TurnArtifacts.EMPTY -> thinking_content / signature both NULL")
    void persistOutbound_withoutThinking_leavesColumnsNull() {
        TurnContext ctx = newCtx();
        TurnOutcome.Success success = new TurnOutcome.Success(
                "Hi! How can I help?", java.util.Map.of());

        Long messageId = persistence.persistOutbound(ctx, success, TurnArtifacts.EMPTY);

        assertThat(messageId).isNotNull();
        ImMessage saved = messageMapper.selectById(messageId);
        assertThat(saved.getThinkingContent())
                .as("no-thinking turn must not write empty string — see schema red line")
                .isNull();
        assertThat(saved.getThinkingSignature()).isNull();
        // The base content path is untouched.
        assertThat(saved.getContent()).isEqualTo("Hi! How can I help?");
    }

    @Test
    @DisplayName("Case B': empty-string thinking input is normalized to NULL (defensive)")
    void persistOutbound_withEmptyStringThinking_writesNull() {
        TurnContext ctx = newCtx();
        TurnOutcome.Success success = new TurnOutcome.Success("ok", java.util.Map.of());

        Long messageId = persistence.persistOutbound(ctx, success, new TurnArtifacts("", ""));

        assertThat(messageId).isNotNull();
        ImMessage saved = messageMapper.selectById(messageId);
        assertThat(saved.getThinkingContent()).isNull();
        assertThat(saved.getThinkingSignature()).isNull();
    }

    // =========================================================================
    // Case C — message-history endpoint surfaces persisted thinking
    // =========================================================================

    @Test
    @DisplayName("Case C: getMessages history endpoint returns thinkingContent on assistant row")
    void getMessages_returnsThinkingContentForAssistantRow() {
        seedInboundUserRow("How does X work?");
        TurnContext ctx = newCtx();
        persistence.persistOutbound(ctx,
                new TurnOutcome.Success("X works like this.", java.util.Map.of()),
                TurnArtifacts.of(
                        "Walking through X mentally — first the input, then the pipeline.",
                        "sig-history-ABC"));

        java.util.List<AuraBotConversationMessage> history = conversationService.getMessages(
                conversationId, tenantId, humanMemberId, 100);

        // Find the assistant row in the returned history.
        AuraBotConversationMessage assistantRow = history.stream()
                .filter(m -> "assistant".equals(m.getSender()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected at least one assistant row in history, got: " + history));
        assertThat(assistantRow.getContent()).isEqualTo("X works like this.");
        assertThat(assistantRow.getThinkingContent())
                .as("history endpoint must surface persisted thinking so the frontend "
                        + "can re-render the reasoning pane after a page reload")
                .isEqualTo("Walking through X mentally — first the input, then the pipeline.");
        assertThat(assistantRow.getThinkingSignature()).isEqualTo("sig-history-ABC");
    }

    // =========================================================================
    // Back-compat — legacy 2-arg overload still works (existing call sites)
    // =========================================================================

    @Test
    @DisplayName("Back-compat: legacy persistOutbound(ctx, outcome) still compiles + leaves thinking NULL")
    void persistOutbound_legacyTwoArgOverload_stillWorks() {
        TurnContext ctx = newCtx();
        TurnOutcome.Success success = new TurnOutcome.Success("plain reply", java.util.Map.of());

        Long messageId = persistence.persistOutbound(ctx, success);

        assertThat(messageId).isNotNull();
        ImMessage saved = messageMapper.selectById(messageId);
        assertThat(saved.getContent()).isEqualTo("plain reply");
        assertThat(saved.getThinkingContent())
                .as("legacy 2-arg overload must default to NULL thinking — "
                        + "regression sentinel for callers that have not migrated yet")
                .isNull();
        assertThat(saved.getThinkingSignature()).isNull();
    }
}
