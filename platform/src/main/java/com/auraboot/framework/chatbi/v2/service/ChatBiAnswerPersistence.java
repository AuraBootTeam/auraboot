package com.auraboot.framework.chatbi.v2.service;

import com.auraboot.framework.chatbi.v2.entity.ChatBiAnswer;
import com.auraboot.framework.chatbi.v2.mapper.ChatBiAnswerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Best-effort persistence for ChatBI answers, isolated in
 * {@link Propagation#REQUIRES_NEW} so a write failure here can never poison the
 * caller's answer transaction.
 *
 * <p>Rationale (TX-003): {@link ChatBiAnswerService#ask} is {@code @Transactional}.
 * When the answer-row insert or the conversation append failed while participating
 * in that <em>same</em> transaction, Postgres marked it aborted (25P02) and the
 * outer commit threw {@code UnexpectedRollbackException} — turning a successful
 * answer into a 500 and rolling back the very answer that had just succeeded.
 * Running these writes in a SEPARATE physical transaction (REQUIRES_NEW, on a
 * distinct bean so the Spring proxy is actually applied — a {@code this.} call
 * would not create a new transaction) keeps the outer transaction intact.
 *
 * <p>Mirrors {@link LlmAuditService}, which already isolates its audit writes for
 * the same reason: observability / best-effort persistence MUST NOT fail the
 * user-visible flow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatBiAnswerPersistence {

    private final ChatBiAnswerMapper answerMapper;
    private final ConversationService conversationService;

    /**
     * Insert one {@link ChatBiAnswer} row. Runs in its own transaction; a failure
     * is logged and swallowed so it never fails the caller's answer.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistAnswer(ChatBiAnswer row) {
        try {
            answerMapper.insert(row);
        } catch (Exception e) {
            log.warn("Failed to persist ChatBiAnswer {}: {}",
                    row != null ? row.getPid() : null, e.getMessage());
        }
    }

    /**
     * Append the (user, assistant) turn pair to the conversation. Runs in its own
     * transaction; a failure is logged and swallowed so it never fails the answer.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void appendTurn(Long tenantId,
                           String conversationPid,
                           String userMessage,
                           String assistantMessage) {
        try {
            conversationService.append(tenantId, conversationPid, "user", userMessage);
            conversationService.append(tenantId, conversationPid, "assistant", assistantMessage);
        } catch (Exception e) {
            log.warn("Failed to append answer to conversation {}: {}",
                    conversationPid, e.getMessage());
        }
    }
}
