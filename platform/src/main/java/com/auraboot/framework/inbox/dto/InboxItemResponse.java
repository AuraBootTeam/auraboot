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

    /**
     * @deprecated Use {@link #summary} instead. Mobile clients already prefer
     *     {@code summary} over {@code subtitle} when both are present
     *     (Android {@code InboxItemDTO.toDomain} and iOS
     *     {@code InboxItem.summary} fall back to {@code subtitle} only when
     *     summary is null). Scheduled for removal after release 6.5 — see
     *     {@code docs/mobile/legacy-field-deprecation.md}.
     */
    @Deprecated(since = "6.4")
    private String subtitle;

    private String summary;
    private String status;
    private String priority;
    private String sourceType;
    private String sourceId;

    /**
     * @deprecated Use {@link #sourceModel} instead. Both fields carry the
     *     same value today (see {@link #from(InboxItem)}); {@code modelCode}
     *     is the legacy field name that predates the BFF DTO formalisation.
     *     Scheduled for removal after release 6.5 — see
     *     {@code docs/mobile/legacy-field-deprecation.md}.
     */
    @Deprecated(since = "6.4")
    private String modelCode;

    private String sourceModel;

    /**
     * @deprecated Use {@link #sourceRecordId} (string) instead. The numeric
     *     {@code recordId} column was a leftover from when {@code ab_inbox_item}
     *     stored {@code BIGINT}-only record references; production records
     *     are ULIDs (string) and the BFF only emits a numeric value when the
     *     legacy column happens to be set. Mobile DTOs already prefer
     *     {@code sourceRecordId} (Android {@code InboxItemDTO.toDomain} L61,
     *     iOS {@code InboxItem.sourceRecordId}). Scheduled for removal after
     *     release 6.5 — see {@code docs/mobile/legacy-field-deprecation.md}.
     */
    @Deprecated(since = "6.4")
    private Long recordId;

    private String sourceRecordId;

    /**
     * @deprecated Use {@link #cardData} (parsed Map) instead. {@code cardPayload}
     *     is the raw JSON string stored in {@code ab_inbox_item.card_payload};
     *     the BFF parses it once and exposes the parsed map via
     *     {@link #cardData}. No mobile renderer reads {@code cardPayload}
     *     directly today (Android {@code InboxItemDTO.cardPayload} only
     *     fills {@link #cardData} when the parsed map is missing).
     *     Scheduled for removal after release 6.5 — see
     *     {@code docs/mobile/legacy-field-deprecation.md}.
     */
    @Deprecated(since = "6.4")
    private String cardPayload;

    /**
     * Free-form card payload parsed from {@link #cardPayload}, scoped per
     * {@code itemType} / industry template (CRM / ERP / ...).
     *
     * <h3>Reserved key — {@code actions[]}</h3>
     *
     * Mobile and web clients render quick-action buttons on each inbox card
     * by reading {@code cardData.actions}. The shape is fixed across all
     * industry templates:
     *
     * <pre>
     * "actions": [
     *   { "action": "approve", "label": "Approve", "style": "primary" },
     *   { "action": "reject",  "label": "Reject",  "style": "destructive" }
     * ]
     * </pre>
     *
     * <ul>
     *   <li>{@code action} <i>(required, non-blank)</i> — action verb consumed
     *       by the client (typically maps to a backend command id or to a
     *       client-side intent like {@code approve} / {@code reject} /
     *       {@code follow_up}).</li>
     *   <li>{@code label} <i>(required, non-blank)</i> — display text. Use
     *       {@code $i18n:key} or a {@code LocalizedText} object when
     *       internationalisation is required.</li>
     *   <li>{@code style} <i>(optional, default {@code "secondary"})</i> —
     *       one of {@link CardActionStyle#PRIMARY},
     *       {@link CardActionStyle#SECONDARY},
     *       {@link CardActionStyle#DESTRUCTIVE}. Unknown values fall back
     *       to {@code secondary}.</li>
     * </ul>
     *
     * Other keys in {@code cardData} are template-specific and may be added
     * without breaking the {@code actions[]} contract. See
     * {@code docs/mobile/ux/shared/21-industry-layout-config.md} for the
     * per-template field catalogue.
     */
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

    /**
     * Allowed {@code style} values for entries in {@code cardData.actions[]}.
     * Mirrors web/mobile renderers (Android {@code InboxCardAction.Style},
     * iOS {@code CardAction.Style}). Producers of inbox card payloads MUST
     * use one of these values; consumers MUST treat unknown values as
     * {@link #SECONDARY}.
     */
    public enum CardActionStyle {
        PRIMARY,
        SECONDARY,
        DESTRUCTIVE;

        public String wireValue() {
            return name().toLowerCase();
        }
    }
}
