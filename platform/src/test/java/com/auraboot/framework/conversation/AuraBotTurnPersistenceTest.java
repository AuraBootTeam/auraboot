package com.auraboot.framework.conversation;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.dto.AuraBotConversationItem;
import com.auraboot.framework.aurabot.service.AuraBotConversationService;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B.1 integration test for {@link AuraBotTurnPersistence}.
 *
 * <p>Asserts the server-side persistence contract that replaced the
 * frontend-driven {@code appendUserMessage} / {@code appendAssistantMessage}
 * detour:
 * <ol>
 *     <li>{@link AuraBotTurnPersistence#persistInbound} writes a
 *         {@code sender_type='human'} row keyed by {@code (conversationId, clientMsgId)}.</li>
 *     <li>{@link AuraBotTurnPersistence#persistOutbound} on a
 *         {@link TurnOutcome.Success} writes a {@code sender_type='agent'} row
 *         with {@code sender_id=resolved agentId}, {@code message_type='ai_response'}.</li>
 *     <li>{@link AuraBotTurnPersistence#persistOutbound} on a
 *         {@link TurnOutcome.Failed} writes a {@code sender_type='system'} row
 *         (matching the historical assistant-error rendering surface).</li>
 *     <li>Calling {@code persistInbound} twice with the same {@code clientMsgId}
 *         is idempotent — second call returns the same row id thanks to
 *         {@code idx_ab_im_message_dedup}.</li>
 *     <li>When the request did not carry a {@code conversationId}, persistence
 *         silently skips (legacy compat path).</li>
 * </ol>
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
@DisplayName("AuraBotTurnPersistence — server-side ab_im_message writes")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuraBotTurnPersistenceTest extends BaseIntegrationTest {

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
                null, null,
                conversationId,
                null,
                null,
                null,
                Instant.now());
    }

    private TurnRequest newTurnRequest(String message, String clientMsgId) {
        return newTurnRequest(message, clientMsgId, conversationId, humanMemberId);
    }

    private TurnRequest newTurnRequest(String message, String clientMsgId,
                                         Long convId, Long memberId) {
        com.auraboot.framework.aurabot.dto.ChatRequest legacy =
                new com.auraboot.framework.aurabot.dto.ChatRequest();
        legacy.setMessage(message);
        legacy.setSessionId("persistence-test");
        legacy.setAgentCode("aurabot");
        legacy.setConversationId(convId);
        legacy.setClientMsgId(clientMsgId);
        return new TurnRequest(
                tenantId,
                getTestUser().getId(),
                memberId,
                "web",
                "aurabot",
                convId,
                clientMsgId,
                message,
                null, null,
                InboundMode.NEW_FROM_REQUEST,
                null,
                legacy);
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("persistInbound -> ab_im_message row sender_type=human + content + dedup key")
    void persistInbound_writesHumanRow() {
        String clientMsgId = "test-inbound-" + System.nanoTime();

        Long messageId = persistence.persistInbound(newTurnRequest("Hello AuraBot", clientMsgId), null);

        assertThat(messageId).as("inbound message id").isNotNull();
        ImMessage saved = messageMapper.selectById(messageId);
        assertThat(saved).isNotNull();
        assertThat(saved.getSenderType()).isEqualTo("human");
        assertThat(saved.getSenderId()).isEqualTo(humanMemberId);
        assertThat(saved.getContent()).isEqualTo("Hello AuraBot");
        assertThat(saved.getClientMsgId()).isEqualTo(clientMsgId);
        assertThat(saved.getConversationId()).isEqualTo(conversationId);
        assertThat(saved.getMessageType()).isEqualTo("text");
    }

    @Test
    @DisplayName("persistInbound twice w/ same clientMsgId -> idempotent (same row id)")
    void persistInbound_idempotentByClientMsgId() {
        String clientMsgId = "dedup-test-" + System.nanoTime();

        Long firstId = persistence.persistInbound(newTurnRequest("Same message", clientMsgId), null);
        Long secondId = persistence.persistInbound(newTurnRequest("Same message", clientMsgId), null);

        assertThat(firstId).isEqualTo(secondId);
    }

    @Test
    @DisplayName("persistOutbound(Success) -> sender_type=agent, message_type=ai_response, agentId resolved")
    void persistOutboundSuccess_writesAgentRow() {
        TurnContext ctx = newCtx();
        TurnOutcome.Success success = new TurnOutcome.Success("Hi! How can I help?", java.util.Map.of());

        Long messageId = persistence.persistOutbound(ctx, success);

        assertThat(messageId).isNotNull();
        ImMessage saved = messageMapper.selectById(messageId);
        assertThat(saved).isNotNull();
        assertThat(saved.getSenderType()).isEqualTo("agent");
        assertThat(saved.getSenderId()).as("resolved agentId is non-zero").isGreaterThan(0L);
        assertThat(saved.getContent()).isEqualTo("Hi! How can I help?");
        assertThat(saved.getMessageType()).isEqualTo("ai_response");
        assertThat(saved.getConversationId()).isEqualTo(conversationId);
    }

    @Test
    @DisplayName("persistOutbound(Failed) -> sender_type=system row carries error message")
    void persistOutboundFailed_writesSystemRow() {
        TurnContext ctx = newCtx();
        TurnOutcome.Failed failed = new TurnOutcome.Failed("LLM provider timeout", new RuntimeException("boom"));

        Long messageId = persistence.persistOutbound(ctx, failed);

        assertThat(messageId).isNotNull();
        ImMessage saved = messageMapper.selectById(messageId);
        assertThat(saved.getSenderType()).isEqualTo("system");
        assertThat(saved.getContent()).isEqualTo("LLM provider timeout");
        assertThat(saved.getMessageType()).isEqualTo("system");
    }

    @Test
    @DisplayName("persistInbound w/ null conversationId -> NOOP (legacy compat path)")
    void persistInbound_nullConversationId_skipsSilently() {
        Long messageId = persistence.persistInbound(
                newTurnRequest("anything", "client-1", null, humanMemberId), null);

        assertThat(messageId).isNull();
    }

    @Test
    @DisplayName("persistInbound w/ null humanMemberId -> NOOP (defensive)")
    void persistInbound_nullHumanMemberId_skipsSilently() {
        Long messageId = persistence.persistInbound(
                newTurnRequest("anything", "client-2", conversationId, null), null);

        assertThat(messageId).isNull();
    }

    @Test
    @DisplayName("Phase C.1: persistInbound writes triage_bucket / triage_confidence / triage_reason_codes")
    void persistInbound_withTriageVerdict_writesTriageColumns() {
        String clientMsgId = "triage-test-" + System.nanoTime();
        com.auraboot.framework.agent.triage.TriageVerdict verdict =
                new com.auraboot.framework.agent.triage.TriageVerdict(
                        com.auraboot.framework.agent.triage.TriageBucket.LIGHT_CHAT,
                        0.92,
                        java.util.List.of("greeting", "no_platform_keyword"),
                        java.util.Set.of());

        Long messageId = persistence.persistInbound(
                newTurnRequest("你好", clientMsgId), verdict);

        assertThat(messageId).isNotNull();
        ImMessage saved = messageMapper.selectById(messageId);
        assertThat(saved.getTriageBucket()).isEqualTo("light_chat");
        assertThat(saved.getTriageConfidence()).isEqualByComparingTo(java.math.BigDecimal.valueOf(0.92));
        assertThat(saved.getTriageReasonCodes()).contains("greeting").contains("no_platform_keyword");
    }

    @Test
    @DisplayName("Phase C.1: persistInbound w/ null TriageVerdict leaves triage columns null")
    void persistInbound_nullVerdict_triageColumnsRemainNull() {
        String clientMsgId = "no-triage-" + System.nanoTime();

        Long messageId = persistence.persistInbound(
                newTurnRequest("anything", clientMsgId), null);

        assertThat(messageId).isNotNull();
        ImMessage saved = messageMapper.selectById(messageId);
        assertThat(saved.getTriageBucket()).isNull();
        assertThat(saved.getTriageConfidence()).isNull();
        assertThat(saved.getTriageReasonCodes()).isNull();
    }
}
