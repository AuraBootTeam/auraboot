package com.auraboot.framework.tax.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.tax.dto.EInvoiceGenerateRequest;
import com.auraboot.framework.tax.dto.VatCalculationResult;
import com.auraboot.framework.tax.mapper.TaxEInvoiceMapper;
import com.auraboot.framework.tax.mapper.TaxVatRateMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for TaxInvoiceService and VatCalculationService.
 * Uses real PostgreSQL database (no mocks for DB/Redis).
 */
class TaxInvoiceServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TaxInvoiceService taxInvoiceService;

    @Autowired
    private VatCalculationService vatCalculationService;

    @Autowired
    private TaxVatRateMapper vatRateMapper;

    @Autowired
    private TaxEInvoiceMapper einvoiceMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String testVatRateCode;
    private String testEInvoicePid;
    private Long tenantId;

    @BeforeEach
    void setupTaxData() {
        tenantId = getTestTenant().getId();

        // Create test VAT rate
        testVatRateCode = "test_vat_13_" + System.currentTimeMillis();
        String vatRatePid = "vr_" + System.currentTimeMillis();

        jdbcTemplate.update(
                "INSERT INTO mt_tax_vat_rate " +
                        "(pid, tenant_id, tax_vr_code, tax_vr_name, tax_vr_rate_pct, " +
                        "tax_vr_category, tax_vr_tax_type, tax_vr_effective_date, " +
                        "tax_vr_is_default, tax_vr_is_active, deleted_flag) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                vatRatePid, tenantId, testVatRateCode,
                "General VAT 13%", new BigDecimal("13.00"),
                "output_tax", "general_vat",
                LocalDate.of(2024, 1, 1),
                true, true, false
        );

        // Create test e-invoice
        testEInvoicePid = "ei_" + System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO mt_tax_einvoice " +
                        "(pid, tenant_id, tax_ei_einvoice_no, tax_ei_invoice_type, " +
                        "tax_ei_buyer_name, tax_ei_seller_name, tax_ei_status, " +
                        "tax_ei_subtotal_amount, tax_ei_tax_total_amount, tax_ei_total_amount, " +
                        "tax_ei_is_redline, tax_ei_currency_code, deleted_flag) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                testEInvoicePid, tenantId,
                "INV-" + System.currentTimeMillis(), "special_vat",
                "Test Buyer Co.", "Test Seller Co.", "draft",
                new BigDecimal("10000.00"), new BigDecimal("1300.00"), new BigDecimal("11300.00"),
                false, "cny", false
        );

        // Create test invoice line
        String linePid = "eil_" + System.currentTimeMillis();
        jdbcTemplate.update(
                "INSERT INTO mt_tax_einvoice_line " +
                        "(pid, tenant_id, tax_eil_einvoice_id, tax_eil_line_no, " +
                        "tax_eil_item_name, tax_eil_item_code, tax_eil_unit, " +
                        "tax_eil_quantity, tax_eil_unit_price, tax_eil_amount, " +
                        "tax_eil_vat_rate_pct, tax_eil_vat_amount, deleted_flag) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                linePid, tenantId, testEInvoicePid, 1,
                "Test Product", "tp001", "pcs",
                new BigDecimal("100"), new BigDecimal("100.00"), new BigDecimal("10000.00"),
                new BigDecimal("13.00"), new BigDecimal("1300.00"), false
        );
    }

    // ==================== VatCalculationService Tests ====================

    @Test
    @Order(1)
    @DisplayName("Calculate VAT for 13% rate")
    void testCalculateVat13Percent() {
        VatCalculationResult result = vatCalculationService.calculateVat(
                new BigDecimal("10000.00"), testVatRateCode);

        assertThat(result).isNotNull();
        assertThat(result.getAmount()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(result.getVatRate()).isEqualByComparingTo(new BigDecimal("13.00"));
        assertThat(result.getVatAmount()).isEqualByComparingTo(new BigDecimal("1300.00"));
        assertThat(result.getTotalAmount()).isEqualByComparingTo(new BigDecimal("11300.00"));
        assertThat(result.getVatRateCode()).isEqualTo(testVatRateCode);
    }

    @Test
    @Order(2)
    @DisplayName("Calculate VAT with non-existent rate code throws exception")
    void testCalculateVatNonExistentRate() {
        assertThatThrownBy(() ->
                vatCalculationService.calculateVat(new BigDecimal("1000"), "nonexistent_rate"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VAT rate not found");
    }

    @Test
    @Order(3)
    @DisplayName("Calculate line VAT for multiple lines")
    void testCalculateLineVat() {
        List<Map<String, Object>> lines = List.of(
                Map.of("amount", new BigDecimal("5000.00"), "vatRateCode", testVatRateCode),
                Map.of("amount", new BigDecimal("3000.00"), "vatRateCode", testVatRateCode),
                Map.of("amount", new BigDecimal("2000.00"), "vatRateCode", "")
        );

        List<VatCalculationResult> results = vatCalculationService.calculateLineVat(lines);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).getVatAmount()).isEqualByComparingTo(new BigDecimal("650.00"));
        assertThat(results.get(1).getVatAmount()).isEqualByComparingTo(new BigDecimal("390.00"));
        assertThat(results.get(2).getVatAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ==================== TaxInvoiceService Tests ====================

    @Test
    @Order(10)
    @DisplayName("Generate e-invoice from manual input")
    void testGenerateEInvoice() {
        EInvoiceGenerateRequest request = new EInvoiceGenerateRequest();
        request.setSourceType("manual");
        request.setInvoiceType("electronic");
        request.setBuyerName("Test Corp");
        request.setBuyerUscc("91310000XXXXXXXXXX");
        request.setSellerName("My Company");
        request.setSellerUscc("91310000YYYYYYYYYY");

        Map<String, Object> result = taxInvoiceService.generateEInvoice(request);

        assertThat(result).isNotNull();
        assertThat(result.get("tax_ei_serial_no")).asString().startsWith("EI-");
        assertThat(result.get("tax_ei_status")).isEqualTo("draft");
        assertThat(result.get("tax_ei_buyer_name")).isEqualTo("Test Corp");
        assertThat(result.get("tax_ei_seller_name")).isEqualTo("My Company");
        assertThat(result.get("tax_ei_is_redline")).isEqualTo(false);
        assertThat(result.get("tax_ei_currency_code")).isEqualTo("cny");
    }

    @Test
    @Order(11)
    @DisplayName("Issue e-invoice transitions status from DRAFT to ISSUED")
    void testIssueEInvoice() {
        String qrCode = taxInvoiceService.issueEInvoice(testEInvoicePid);

        assertThat(qrCode).isNotBlank();
        assertThat(qrCode).contains(","); // QR code data is comma-separated

        // Verify status changed in DB
        Map<String, Object> updated = einvoiceMapper.findByPid(tenantId, testEInvoicePid);
        assertThat(updated).isNotNull();
        assertThat(updated.get("tax_ei_status")).isEqualTo("issued");
        assertThat(updated.get("tax_ei_qr_code_data")).isEqualTo(qrCode);
    }

    @Test
    @Order(12)
    @DisplayName("Issue e-invoice from non-DRAFT status throws exception")
    void testIssueEInvoiceWrongStatus() {
        // First issue the invoice
        taxInvoiceService.issueEInvoice(testEInvoicePid);

        // Try to issue again — should fail
        assertThatThrownBy(() -> taxInvoiceService.issueEInvoice(testEInvoicePid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issued");
    }

    @Test
    @Order(13)
    @DisplayName("Issue non-existent e-invoice throws exception")
    void testIssueNonExistentEInvoice() {
        assertThatThrownBy(() -> taxInvoiceService.issueEInvoice("nonexistent_pid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @Order(20)
    @DisplayName("Build Golden Tax XML format")
    void testBuildInvoiceXml() {
        String xml = taxInvoiceService.buildInvoiceXml(testEInvoicePid);

        assertThat(xml).isNotBlank();
        assertThat(xml).startsWith("<?xml version=\"1.0\"");
        assertThat(xml).contains("<Fpxx>");
        assertThat(xml).contains("<Gfmc>Test Buyer Co.</Gfmc>");
        assertThat(xml).contains("<Xfmc>Test Seller Co.</Xfmc>");
        assertThat(xml).contains("<Spmc>Test Product</Spmc>");
        assertThat(xml).contains("<Spbm>tp001</Spbm>");
        assertThat(xml).contains("<Se>1300.00</Se>");
        assertThat(xml).contains("</Fpxx>");
    }

    @Test
    @Order(21)
    @DisplayName("Build XML for non-existent e-invoice throws exception")
    void testBuildXmlNonExistent() {
        assertThatThrownBy(() -> taxInvoiceService.buildInvoiceXml("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @Order(30)
    @DisplayName("Build preview HTML")
    void testBuildPreviewHtml() {
        String html = taxInvoiceService.buildPreviewHtml(testEInvoicePid);

        assertThat(html).isNotBlank();
        assertThat(html).contains("<!DOCTYPE html>");
        assertThat(html).contains("Test Buyer Co.");
        assertThat(html).contains("Test Seller Co.");
        assertThat(html).contains("Test Product");
        assertThat(html).contains("tp001");
    }

    // ==================== Mapper Tests ====================

    @Test
    @Order(40)
    @DisplayName("VAT rate mapper - findByCode returns correct rate")
    void testVatRateMapperFindByCode() {
        Map<String, Object> rate = vatRateMapper.findByCode(tenantId, testVatRateCode);

        assertThat(rate).isNotNull();
        assertThat(rate.get("tax_vr_code")).isEqualTo(testVatRateCode);
        assertThat(new BigDecimal(rate.get("tax_vr_rate_pct").toString()))
                .isEqualByComparingTo(new BigDecimal("13.00"));
        assertThat(rate.get("tax_vr_category")).isEqualTo("output_tax");
    }

    @Test
    @Order(41)
    @DisplayName("VAT rate mapper - findActiveRates returns active rates")
    void testVatRateMapperFindActiveRates() {
        List<Map<String, Object>> rates = vatRateMapper.findActiveRates(tenantId);

        assertThat(rates).isNotEmpty();
        // Should include our test rate
        boolean foundTestRate = rates.stream()
                .anyMatch(r -> testVatRateCode.equals(r.get("tax_vr_code")));
        assertThat(foundTestRate).isTrue();
    }

    @Test
    @Order(42)
    @DisplayName("E-Invoice mapper - findByPid returns invoice with correct fields")
    void testEInvoiceMapperFindByPid() {
        Map<String, Object> invoice = einvoiceMapper.findByPid(tenantId, testEInvoicePid);

        assertThat(invoice).isNotNull();
        assertThat(invoice.get("tax_ei_buyer_name")).isEqualTo("Test Buyer Co.");
        assertThat(invoice.get("tax_ei_status")).isEqualTo("draft");
        assertThat(invoice.get("tax_ei_invoice_type")).isEqualTo("special_vat");
    }

    @Test
    @Order(43)
    @DisplayName("E-Invoice mapper - findLinesByEInvoicePid returns lines")
    void testEInvoiceMapperFindLines() {
        List<Map<String, Object>> lines = einvoiceMapper.findLinesByEInvoicePid(tenantId, testEInvoicePid);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("tax_eil_item_name")).isEqualTo("Test Product");
        assertThat(lines.get(0).get("tax_eil_line_no")).isEqualTo(1);
    }

    @Test
    @Order(44)
    @DisplayName("E-Invoice mapper - findByStatus returns matching invoices")
    void testEInvoiceMapperFindByStatus() {
        List<Map<String, Object>> drafts = einvoiceMapper.findByStatus(tenantId, "draft");

        assertThat(drafts).isNotEmpty();
        boolean foundTestInvoice = drafts.stream()
                .anyMatch(inv -> testEInvoicePid.equals(inv.get("pid")));
        assertThat(foundTestInvoice).isTrue();
    }
}
