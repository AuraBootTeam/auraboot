package com.auraboot.framework.inbox.model;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;
import java.util.Map;

/**
 * Materialized inbox item — represents a single actionable item
 * in a user's unified action queue.
 *
 * @since 6.3.0
 */
@Data
@TableName(value = "ab_inbox_item", autoResultMap = true)
public class InboxItem {

    private static final ObjectMapper CARD_PAYLOAD_MAPPER = new ObjectMapper();

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("user_id")
    private Long userId;

    @TableField("item_type")
    private String itemType; // APPROVAL, TASK, MENTION, AI_SUGGESTION, ALERT, ASSIGNMENT

    @TableField("title")
    private String title;

    @TableField("subtitle")
    private String subtitle;

    @TableField("priority")
    private String priority; // LOW, NORMAL, HIGH, URGENT

    @TableField("status")
    private String status; // PENDING, ACTED, DISMISSED, EXPIRED

    // Source reference
    @TableField("source_type")
    private String sourceType; // bpm, im, command, ai, notification

    @TableField("source_id")
    private String sourceId;

    @TableField("model_code")
    private String modelCode;

    @TableField("record_id")
    private Long recordId;

    // Card Protocol JSON
    @TableField(value = "card_payload", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String cardPayload;

    // Action tracking
    @TableField("action_taken")
    private String actionTaken;

    @TableField("acted_at")
    private Instant actedAt;

    // Deep link
    @TableField("deep_link")
    private String deepLink;

    // Read state
    @TableField("is_read")
    private Boolean isRead;

    @TableField("read_at")
    private Instant readAt;

    // Timestamps
    @TableField("created_at")
    private Instant createdAt;

    @TableField("expires_at")
    private Instant expiresAt;

    // Dedup
    @TableField("client_item_id")
    private String clientItemId;

    @JsonProperty("summary")
    public String getSummary() {
        return subtitle;
    }

    @JsonProperty("sourceModel")
    public String getSourceModel() {
        return modelCode;
    }

    @JsonProperty("sourceRecordId")
    public String getSourceRecordId() {
        return recordId != null ? String.valueOf(recordId) : null;
    }

    @JsonProperty("cardData")
    public Map<String, Object> getCardData() {
        if (cardPayload == null || cardPayload.isBlank()) {
            return null;
        }
        try {
            return CARD_PAYLOAD_MAPPER.readValue(cardPayload, new TypeReference<>() {});
        } catch (Exception ignored) {
            return null;
        }
    }
}
