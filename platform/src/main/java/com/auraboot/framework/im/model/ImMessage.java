package com.auraboot.framework.im.model;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

@Data
@TableName(value = "ab_im_message", autoResultMap = true)
public class ImMessage {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("conversation_id")
    private Long conversationId;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("sender_id")
    private Long senderId;

    @TableField("sender_type")
    private String senderType; // human | agent | system

    @TableField("seq")
    private Long seq;

    @TableField("message_type")
    private String messageType; // TEXT | IMAGE | FILE | CARD | SYSTEM | AI_RESPONSE

    @TableField("content")
    private String content;

    @TableField(value = "card_payload", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String cardPayload; // JSONB as string

    @TableField(value = "attachments", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String attachments; // JSONB as string

    @TableField("reply_to_id")
    private Long replyToId;

    @TableField(value = "mentions", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String mentions; // JSONB as string

    @TableField("client_msg_id")
    private String clientMsgId;

    @TableField("recalled")
    private Boolean recalled;

    @TableField("forwarded_from_id")
    private Long forwardedFromId;

    @TableField("created_at")
    private Instant createdAt;

    /**
     * Phase C.1: Pre-Grounding Triage Stage 2.5 verdict snapshot persisted on
     * inbound rows so downstream analytics / audit can reconstruct the routing
     * decision the chokepoint took. Storage shape matches the
     * {@code ab_im_message_triage_bucket_check} CHECK constraint
     * (light_chat / contextual_answer / acp_run, lowercase).
     */
    @TableField("triage_bucket")
    private String triageBucket;

    @TableField("triage_confidence")
    private java.math.BigDecimal triageConfidence;

    @TableField(value = "triage_reason_codes", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String triageReasonCodes;

    /**
     * Phase D.1 (ACP backlog 2026-05-07): Anthropic Extended Thinking reasoning
     * prose for this assistant row, concatenated across all thinking content
     * blocks the turn produced. Null on turns that produced no thinking (we
     * deliberately do not poison with empty strings — see schema column doc).
     */
    @TableField("thinking_content")
    private String thinkingContent;

    /**
     * Phase D.1: Anthropic's opaque resume / verification signature for the
     * thinking block. Null when no thinking was produced or when the upstream
     * stream did not surface a {@code signature_delta}.
     */
    @TableField("thinking_signature")
    private String thinkingSignature;
}
