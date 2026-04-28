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
}
