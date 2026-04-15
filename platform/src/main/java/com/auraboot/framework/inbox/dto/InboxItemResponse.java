package com.auraboot.framework.inbox.dto;

import com.auraboot.framework.inbox.model.InboxItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboxItemResponse {

    private static final ObjectMapper CARD_PAYLOAD_MAPPER = new ObjectMapper();

    private Long id;
    private String itemType;
    private String title;
    private String subtitle;
    private String summary;
    private String status;
    private String priority;
    private String sourceType;
    private String sourceId;
    private String modelCode;
    private String sourceModel;
    private Long recordId;
    private String sourceRecordId;
    private String cardPayload;
    private Map<String, Object> cardData;
    private String actionTaken;
    private Instant actedAt;
    private String deepLink;
    private Boolean isRead;
    private Instant readAt;
    private Instant createdAt;
    private Instant expiresAt;
    private String clientItemId;

    public static InboxItemResponse from(InboxItem item) {
        return InboxItemResponse.builder()
                .id(item.getId())
                .itemType(item.getItemType())
                .title(item.getTitle())
                .subtitle(item.getSubtitle())
                .summary(item.getSubtitle())
                .status(item.getStatus())
                .priority(item.getPriority())
                .sourceType(item.getSourceType())
                .sourceId(item.getSourceId())
                .modelCode(item.getModelCode())
                .sourceModel(item.getModelCode())
                .recordId(item.getRecordId())
                .sourceRecordId(item.getRecordId() != null ? String.valueOf(item.getRecordId()) : null)
                .cardPayload(item.getCardPayload())
                .cardData(parseCardPayload(item.getCardPayload()))
                .actionTaken(item.getActionTaken())
                .actedAt(item.getActedAt())
                .deepLink(item.getDeepLink())
                .isRead(item.getIsRead())
                .readAt(item.getReadAt())
                .createdAt(item.getCreatedAt())
                .expiresAt(item.getExpiresAt())
                .clientItemId(item.getClientItemId())
                .build();
    }

    private static Map<String, Object> parseCardPayload(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return CARD_PAYLOAD_MAPPER.readValue(raw, new TypeReference<>() {});
        } catch (Exception ignored) {
            return null;
        }
    }
}
