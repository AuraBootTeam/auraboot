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
 * Archived single ChatBI v2 answer. PRD 17 §5 table 1.
 *
 * <p>Append-only — each NL query produces one row. The {@code tokens_json} +
 * {@code semantic_request_json} pair lets us replay any historic answer
 * deterministically (semantic catalog drift permitting).
 *
 * <p>{@code conversation_pid} is nullable: a one-off "direct ask" without
 * multi-turn context is allowed (PRD §6.1).
 */
@Data
@TableName("chatbi_answer")
public class ChatBiAnswer {

    /** {@code id BIGINT PRIMARY KEY} (not BIGSERIAL) → snowflake. */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;

    private Long tenantId;

    private Long userId;

    /** Nullable; null = non-conversation direct ask. */
    private String conversationPid;

    private String semanticModelPid;

    private String nlQuery;

    /** JSON array of {@link com.auraboot.framework.chatbi.v2.dto.SearchToken}. */
    private String tokensJson;

    /** JSON dump of the compiled {@code SemanticQueryRequest}. */
    private String semanticRequestJson;

    /** SHA-256 fingerprint of the executed SQL, joins to {@code ab_semantic_query_log}. */
    private String sqlHash;

    /** {@code kpi / bar / line / pivot / table}. */
    private String vizType;

    private String vizConfigJson;

    private Integer rowCount;

    private Integer durationMs;

    /** {@code claude-haiku-4-5 / gpt-4o-mini / null}. */
    private String llmUsed;

    private BigDecimal llmCostCents;

    /** {@code SUCCESS / DISAMBIGUATION / FAILED}. */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
