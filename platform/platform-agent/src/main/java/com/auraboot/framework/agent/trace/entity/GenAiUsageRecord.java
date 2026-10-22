package com.auraboot.framework.agent.trace.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Durable LLM usage/cost ledger row — the billing source of truth (A-G6, P1;
 * SoT §2.5). Written per LLM generation, separate from the diagnostic
 * {@code ab_ai_trace_span} so cost/quota is never summed from sampled spans.
 */
@Data
@TableName("ab_gen_ai_usage")
public class GenAiUsageRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private String runId;
    private String traceId;
    private String spanId;
    private String provider;
    private String requestModel;
    private String responseModel;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer cacheReadTokens;
    private Integer cacheWriteTokens;
    private Integer reasoningTokens;
    private BigDecimal amount;
    private String currency;
    private String pricingVersion;

    /** Left null on insert — DB default CURRENT_TIMESTAMP fills it. */
    private Instant createdAt;
}
