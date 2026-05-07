package com.auraboot.framework.inbox.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the typed CRM/ERP card-data POJOs (M-090 DATA-003) match the
 * field catalogue documented in
 * {@code docs/mobile/ux/shared/24-inbox-card-template-catalogue.md}.
 *
 * <p>If a renderer adds a new catalogued field, update both the doc and
 * the corresponding POJO + this test.
 */
class IndustryInboxCardDataTest {

    // ---- CRM ----

    @Test
    void crm_extractsAllCataloguedFieldsFromQuoteKind() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("accountName", "Acme Corp");
        raw.put("quoteAmount", 245000);
        raw.put("quoteCurrency", "USD");
        raw.put("slaHours", 6);

        CrmInboxCardData crm = CrmInboxCardData.from(raw);

        assertNotNull(crm);
        assertEquals("Acme Corp", crm.getAccountName());
        assertEquals(0, BigDecimal.valueOf(245000).compareTo(crm.getQuoteAmount()));
        assertEquals("USD", crm.getQuoteCurrency());
        assertEquals(6, crm.getSlaHours());
        assertNull(crm.getRiskLevel(), "quote kind doesn't carry riskLevel");
    }

    @Test
    void crm_riskLevelEnumDecodesAllThreeValues() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("accountName", "Gamma Holdings");
        raw.put("riskLevel", "high");

        CrmInboxCardData crm = CrmInboxCardData.from(raw);
        assertEquals(CrmInboxCardData.RiskLevel.HIGH, crm.getRiskLevel());

        raw.put("riskLevel", "MEDIUM"); // case-insensitive
        assertEquals(CrmInboxCardData.RiskLevel.MEDIUM, CrmInboxCardData.from(raw).getRiskLevel());

        raw.put("riskLevel", "low");
        assertEquals(CrmInboxCardData.RiskLevel.LOW, CrmInboxCardData.from(raw).getRiskLevel());
    }

    @Test
    void crm_unknownRiskLevelMapsToNull() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("riskLevel", "extreme");
        assertNull(CrmInboxCardData.from(raw).getRiskLevel(),
                "Unknown wire values must map to null so callers can detect drift");
    }

    @Test
    void crm_followUpKindExtractsAccountAndNextStep() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("accountName", "Beta Industries");
        raw.put("nextStep", "Send proposal draft");

        CrmInboxCardData crm = CrmInboxCardData.from(raw);
        assertEquals("Beta Industries", crm.getAccountName());
        assertEquals("Send proposal draft", crm.getNextStep());
        assertNull(crm.getQuoteAmount());
    }

    @Test
    void crm_aiSuggestionKindExtractsAnnotation() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("accountName", "Acme Corp");
        raw.put("aiAnnotation", "92% confidence — premium tier");

        CrmInboxCardData crm = CrmInboxCardData.from(raw);
        assertEquals("92% confidence — premium tier", crm.getAiAnnotation());
    }

    @Test
    void crm_riskLevelWireValuesAreContractStable() {
        // Pinned wire values — changing these breaks every mobile renderer.
        assertEquals("low", CrmInboxCardData.RiskLevel.LOW.wireValue());
        assertEquals("medium", CrmInboxCardData.RiskLevel.MEDIUM.wireValue());
        assertEquals("high", CrmInboxCardData.RiskLevel.HIGH.wireValue());
    }

    @Test
    void crm_typeMismatchDoesNotThrow() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("slaHours", "not a number");
        raw.put("quoteAmount", new Object());
        raw.put("accountName", 12345); // non-string

        CrmInboxCardData crm = CrmInboxCardData.from(raw);
        assertNull(crm.getSlaHours());
        assertNull(crm.getQuoteAmount());
        assertNull(crm.getAccountName());
    }

    @Test
    void crm_nullInputReturnsNull() {
        assertNull(CrmInboxCardData.from(null));
    }

    // ---- ERP ----

    @Test
    void erp_extractsAllCataloguedFieldsFromPurchaseApprovalKind() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("vendor", "Shenzhen Equipments Ltd.");
        raw.put("amount", 128000);
        raw.put("currency", "CNY");
        raw.put("slaHours", 4);

        ErpInboxCardData erp = ErpInboxCardData.from(raw);

        assertEquals("Shenzhen Equipments Ltd.", erp.getVendor());
        assertEquals(0, BigDecimal.valueOf(128000).compareTo(erp.getAmount()));
        assertEquals("CNY", erp.getCurrency());
        assertEquals(4, erp.getSlaHours());
    }

    @Test
    void erp_exceptionTypeEnumDecodesAllValues() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("exceptionType", "quality");
        assertEquals(ErpInboxCardData.ExceptionType.QUALITY,
                ErpInboxCardData.from(raw).getExceptionType());

        raw.put("exceptionType", "DELIVERY");
        assertEquals(ErpInboxCardData.ExceptionType.DELIVERY,
                ErpInboxCardData.from(raw).getExceptionType());

        raw.put("exceptionType", "inventory");
        assertEquals(ErpInboxCardData.ExceptionType.INVENTORY,
                ErpInboxCardData.from(raw).getExceptionType());
    }

    @Test
    void erp_inventoryAlertEnumDecodesUnderscoreNames() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("inventoryAlert", "low_stock");
        assertEquals(ErpInboxCardData.InventoryAlert.LOW_STOCK,
                ErpInboxCardData.from(raw).getInventoryAlert());

        raw.put("inventoryAlert", "stock_out");
        assertEquals(ErpInboxCardData.InventoryAlert.STOCK_OUT,
                ErpInboxCardData.from(raw).getInventoryAlert());

        raw.put("inventoryAlert", "OVERSTOCK"); // case-insensitive
        assertEquals(ErpInboxCardData.InventoryAlert.OVERSTOCK,
                ErpInboxCardData.from(raw).getInventoryAlert());
    }

    @Test
    void erp_inventoryAlertExtractsRelatedSku() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("inventoryAlert", "low_stock");
        raw.put("relatedSku", "SKU-44213");
        assertEquals("SKU-44213", ErpInboxCardData.from(raw).getRelatedSku());
    }

    @Test
    void erp_unknownEnumValuesMapToNull() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("exceptionType", "weather");
        raw.put("inventoryAlert", "expired_lot");
        ErpInboxCardData erp = ErpInboxCardData.from(raw);
        assertNull(erp.getExceptionType());
        assertNull(erp.getInventoryAlert());
    }

    @Test
    void erp_wireValuesAreContractStable() {
        // Pinned — mobile renderers depend on these strings.
        assertEquals("quality", ErpInboxCardData.ExceptionType.QUALITY.wireValue());
        assertEquals("delivery", ErpInboxCardData.ExceptionType.DELIVERY.wireValue());
        assertEquals("inventory", ErpInboxCardData.ExceptionType.INVENTORY.wireValue());
        assertEquals("low_stock", ErpInboxCardData.InventoryAlert.LOW_STOCK.wireValue());
        assertEquals("stock_out", ErpInboxCardData.InventoryAlert.STOCK_OUT.wireValue());
        assertEquals("overstock", ErpInboxCardData.InventoryAlert.OVERSTOCK.wireValue());
    }

    @Test
    void erp_nullInputReturnsNull() {
        assertNull(ErpInboxCardData.from(null));
    }

    // ---- Cross-template ----

    @Test
    void slaHoursAcceptsBothNumberAndStringRepresentations() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("slaHours", 12);
        assertEquals(12, CrmInboxCardData.from(raw).getSlaHours());

        raw.put("slaHours", "24");
        assertEquals(24, CrmInboxCardData.from(raw).getSlaHours());

        raw.put("slaHours", 12.0);
        assertEquals(12, ErpInboxCardData.from(raw).getSlaHours());
    }
}
