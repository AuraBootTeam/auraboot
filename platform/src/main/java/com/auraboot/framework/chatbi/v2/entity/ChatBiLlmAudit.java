package com.auraboot.framework.chatbi.v2.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * LLM cost + latency audit. PRD 17 §5 table 3 + §12 monitoring.
 *
 * <p>Append-only. Joined by {@code answer_pid} to {@link ChatBiAnswer} for
 * per-answer cost attribution; aggregated by {@code (tenant_id, ts)} for the
 * monthly billing dashboard.
 */
@Data
@TableName("chatbi_llm_audit")
public class ChatBiLlmAudit {

    /** {@code id BIGINT PRIMARY KEY} (not BIGSERIAL) → snowflake. */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    private String answerPid;

    private String conversationPid;

    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private BigDecimal costCents;

    private Integer latencyMs;

    private Boolean success;

    private String errorCode;

    @TableField(fill = FieldFill.INSERT)
    private Instant ts;
}
