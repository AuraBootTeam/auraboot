package com.auraboot.framework.inbox.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Typed view of {@link InboxItemResponse#getCardData() InboxItemResponse.cardData}
 * for the <b>CRM</b> industry template.
 *
 * <p>Field catalogue lives in
 * {@code docs/mobile/ux/shared/24-inbox-card-template-catalogue.md} §3.1.
 * Producers (workflow listeners, plugin event handlers) MAY use this class
 * to type-check the cardData they emit; the wire format remains a plain
 * {@code Map<String, Object>} on {@link InboxItemResponse}, so adding new
 * non-catalogued keys does not break clients.
 *
 * <p>Fields are independently optional — different CRM card kinds
 * (quote / follow_up / risk_signal / ai_suggestion) populate different
 * subsets. {@link #from(Map)} extracts whatever is present without
 * throwing on missing values.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrmInboxCardData {

    /** CRM account display name. Used by every CRM card kind. */
    private String accountName;

    /** Next CRM action description ({@code follow_up} kind). */
    private String nextStep;

    /** Risk severity ({@code risk_signal} kind). One of {@link RiskLevel}. */
    private RiskLevel riskLevel;

    /** Service-level-agreement hours remaining. {@code <= 0} suppresses the chip on mobile. */
    private Integer slaHours;

    /** Quote amount in {@link #quoteCurrency} ({@code quote} kind). */
    private BigDecimal quoteAmount;

    /** ISO-4217 currency code paired with {@link #quoteAmount}. */
    private String quoteCurrency;

    /** AI-generated explanation ({@code ai_suggestion} kind). iOS-rendered today. */
    private String aiAnnotation;

    /**
     * Build a {@link CrmInboxCardData} from a raw {@code cardData} map.
     * Returns null when {@code raw} is null. Unknown keys are ignored;
     * type mismatches on known keys yield null for that field.
     */
    public static CrmInboxCardData from(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        return CrmInboxCardData.builder()
                .accountName(asString(raw.get("accountName")))
                .nextStep(asString(raw.get("nextStep")))
                .riskLevel(RiskLevel.fromWire(asString(raw.get("riskLevel"))))
                .slaHours(asInteger(raw.get("slaHours")))
                .quoteAmount(asBigDecimal(raw.get("quoteAmount")))
                .quoteCurrency(asString(raw.get("quoteCurrency")))
                .aiAnnotation(asString(raw.get("aiAnnotation")))
                .build();
    }

    /** Allowed values of {@link CrmInboxCardData#riskLevel}. Unknown wire values map to null. */
    public enum RiskLevel {
        LOW, MEDIUM, HIGH;

        public String wireValue() {
            return name().toLowerCase();
        }

        public static RiskLevel fromWire(String raw) {
            if (raw == null) return null;
            String normalized = raw.trim().toLowerCase();
            for (RiskLevel level : values()) {
                if (level.wireValue().equals(normalized)) {
                    return level;
                }
            }
            return null;
        }
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : null;
    }

    private static Integer asInteger(Object o) {
        if (o instanceof Integer i) return i;
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static BigDecimal asBigDecimal(Object o) {
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (o instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
