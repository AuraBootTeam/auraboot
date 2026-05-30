package com.auraboot.framework.chatbi.v2.service;

import com.auraboot.framework.chatbi.v2.entity.ChatBiLlmAudit;
import com.auraboot.framework.chatbi.v2.mapper.ChatBiLlmAuditMapper;
import com.auraboot.framework.chatbi.v2.provider.LlmUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Writes one {@link ChatBiLlmAudit} row per LLM round-trip. Audits the cost,
 * latency, and outcome so the W4 dashboards + tenant budget guard have a
 * single source of truth.
 *
 * <p>Run in {@link Propagation#REQUIRES_NEW} so a failure to audit never
 * rolls back the surrounding answer transaction — observability MUST NOT
 * cause the user-visible flow to fail.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmAuditService {

    private final ChatBiLlmAuditMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(Long tenantId,
                              String answerPid,
                              String conversationPid,
                              LlmUsage usage) {
        try {
            ChatBiLlmAudit row = baseRow(tenantId, answerPid, conversationPid, usage);
            row.setSuccess(Boolean.TRUE);
            row.setErrorCode(null);
            mapper.insert(row);
        } catch (Exception e) {
            log.warn("LLM audit insert failed (tenant={} answer={}): {}",
                    tenantId, answerPid, e.getMessage());
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long tenantId,
                              String answerPid,
                              String conversationPid,
                              LlmUsage usage,
                              String errorCode) {
        try {
            ChatBiLlmAudit row = baseRow(tenantId, answerPid, conversationPid, usage);
            row.setSuccess(Boolean.FALSE);
            row.setErrorCode(truncate(errorCode, 64));
            mapper.insert(row);
        } catch (Exception e) {
            log.warn("LLM audit failure-insert failed (tenant={} answer={}): {}",
                    tenantId, answerPid, e.getMessage());
        }
    }

    /** Total tokens billed against {@code tenantId} in the last {@code days} days. */
    public long tokensInLastDays(Long tenantId, int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        return mapper.sumTokensSince(tenantId, since);
    }

    public BigDecimal costCentsInLastDays(Long tenantId, int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        BigDecimal v = mapper.sumCostCentsSince(tenantId, since);
        return v != null ? v : BigDecimal.ZERO;
    }

    private ChatBiLlmAudit baseRow(Long tenantId,
                                   String answerPid,
                                   String conversationPid,
                                   LlmUsage usage) {
        ChatBiLlmAudit row = new ChatBiLlmAudit();
        row.setTenantId(tenantId);
        row.setAnswerPid(answerPid);
        row.setConversationPid(conversationPid);
        row.setModel(usage.model());
        row.setPromptTokens(usage.promptTokens());
        row.setCompletionTokens(usage.completionTokens());
        row.setTotalTokens(usage.totalTokens());
        row.setCostCents(BigDecimal.valueOf(usage.costCents()));
        row.setLatencyMs((int) Math.min(Integer.MAX_VALUE, usage.latencyMs()));
        return row;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
