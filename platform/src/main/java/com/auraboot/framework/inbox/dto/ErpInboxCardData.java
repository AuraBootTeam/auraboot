package com.auraboot.framework.inbox.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Typed view of {@link InboxItemResponse#getCardData() InboxItemResponse.cardData}
 * for the <b>ERP</b> industry template.
 *
 * <p>Field catalogue lives in
 * {@code docs/mobile/ux/shared/24-inbox-card-template-catalogue.md} §4.1.
 * Producers (workflow listeners, plugin event handlers) MAY use this class
 * to type-check the cardData they emit; the wire format remains a plain
 * {@code Map<String, Object>} on {@link InboxItemResponse}, so adding new
 * non-catalogued keys does not break clients.
 *
 * <p>Fields are independently optional — different ERP card kinds
 * (purchase_approval / inventory_alert / exception_card) populate different
 * subsets.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErpInboxCardData {

    /** Vendor display name ({@code purchase_approval} / {@code exception_card} kinds). */
    private String vendor;

    /** Amount in {@link #currency} ({@code purchase_approval} kind). */
    private BigDecimal amount;

    /** ISO-4217 currency code paired with {@link #amount}. */
    private String currency;

    /** Service-level-agreement hours remaining. {@code <= 0} suppresses the chip on mobile. */
    private Integer slaHours;

    /** Exception classification ({@code exception_card} kind). One of {@link ExceptionType}. */
    private ExceptionType exceptionType;

    /** Inventory alert classification ({@code inventory_alert} kind). One of {@link InventoryAlert}. */
    private InventoryAlert inventoryAlert;

    /** Related SKU code ({@code inventory_alert} kind). */
    private String relatedSku;

    public static ErpInboxCardData from(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        return ErpInboxCardData.builder()
                .vendor(asString(raw.get("vendor")))
                .amount(asBigDecimal(raw.get("amount")))
                .currency(asString(raw.get("currency")))
                .slaHours(asInteger(raw.get("slaHours")))
                .exceptionType(ExceptionType.fromWire(asString(raw.get("exceptionType"))))
                .inventoryAlert(InventoryAlert.fromWire(asString(raw.get("inventoryAlert"))))
                .relatedSku(asString(raw.get("relatedSku")))
                .build();
    }

    /** Exception classification — drives the leading icon on {@code exception_card}. */
    public enum ExceptionType {
        QUALITY, DELIVERY, INVENTORY;

        public String wireValue() {
            return name().toLowerCase();
        }

        public static ExceptionType fromWire(String raw) {
            if (raw == null) return null;
            String n = raw.trim().toLowerCase();
            for (ExceptionType e : values()) {
                if (e.wireValue().equals(n)) return e;
            }
            return null;
        }
    }

    /** Inventory alert classification. */
    public enum InventoryAlert {
        LOW_STOCK("low_stock"),
        STOCK_OUT("stock_out"),
        OVERSTOCK("overstock");

        private final String wire;

        InventoryAlert(String wire) {
            this.wire = wire;
        }

        public String wireValue() {
            return wire;
        }

        public static InventoryAlert fromWire(String raw) {
            if (raw == null) return null;
            String n = raw.trim().toLowerCase();
            for (InventoryAlert a : values()) {
                if (a.wire.equals(n)) return a;
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
