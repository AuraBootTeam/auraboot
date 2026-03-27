package com.auraboot.framework.tax.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.tax.dto.EInvoiceGenerateRequest;
import com.auraboot.framework.tax.mapper.TaxEInvoiceMapper;
import com.auraboot.framework.currency.spi.CurrencyConversionSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Service for e-invoice generation, issuance, and Golden Tax XML building.
 * Phase 1: Core generation logic. Tax bureau API integration is stubbed.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxInvoiceService {

    private final TaxEInvoiceMapper einvoiceMapper;
    private final VatCalculationService vatCalculationService;
    private final CurrencyConversionSpi currencyConversionService;

    /**
     * Generate e-invoice data from a source document or manual input.
     * Returns a map suitable for creating an e-invoice record via DynamicDataService.
     *
     * @param request the generation request
     * @return e-invoice field map ready for insert
     */
    public Map<String, Object> generateEInvoice(EInvoiceGenerateRequest request) {
        String serialNo = "EI-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + UniqueIdGenerator.generate().substring(0, 8);

        Map<String, Object> einvoice = new HashMap<>();
        einvoice.put("tax_ei_serial_no", serialNo);
        einvoice.put("tax_ei_einvoice_no", serialNo);
        einvoice.put("tax_ei_source_type", request.getSourceType());
        einvoice.put("tax_ei_source_id", request.getSourceId());
        einvoice.put("tax_ei_invoice_type", request.getInvoiceType());
        einvoice.put("tax_ei_buyer_name", request.getBuyerName());
        einvoice.put("tax_ei_buyer_uscc", request.getBuyerUscc());
        einvoice.put("tax_ei_buyer_address", request.getBuyerAddress());
        einvoice.put("tax_ei_buyer_bank", request.getBuyerBank());
        einvoice.put("tax_ei_seller_name", request.getSellerName());
        einvoice.put("tax_ei_seller_uscc", request.getSellerUscc());
        einvoice.put("tax_ei_seller_address", request.getSellerAddress());
        einvoice.put("tax_ei_seller_bank", request.getSellerBank());
        einvoice.put("tax_ei_currency_code", request.getCurrencyCode() != null ? request.getCurrencyCode() : currencyConversionService.getBaseCurrency());
        einvoice.put("tax_ei_remarks", request.getRemarks());
        einvoice.put("tax_ei_status", "draft");
        einvoice.put("tax_ei_is_redline", false);
        einvoice.put("tax_ei_subtotal_amount", BigDecimal.ZERO);
        einvoice.put("tax_ei_tax_total_amount", BigDecimal.ZERO);
        einvoice.put("tax_ei_total_amount", BigDecimal.ZERO);

        log.info("Generated e-invoice data: serialNo={}, sourceType={}, sourceId={}",
                serialNo, request.getSourceType(), request.getSourceId());

        return einvoice;
    }

    /**
     * Issue an e-invoice: change status to ISSUED, generate QR code data.
     *
     * @param einvoicePid the e-invoice PID
     * @return the QR code data string
     */
    public String issueEInvoice(String einvoicePid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, Object> einvoice = einvoiceMapper.findByPid(tenantId, einvoicePid);

        if (einvoice == null) {
            throw new IllegalArgumentException("E-Invoice not found: " + einvoicePid);
        }

        String currentStatus = (String) einvoice.get("tax_ei_status");
        if (!StatusConstants.DRAFT.equals(currentStatus)) {
            throw new IllegalStateException("E-Invoice can only be issued from DRAFT status, current: " + currentStatus);
        }

        // Generate QR code data (simplified: invoice identifier for scanning)
        String qrCodeData = buildQrCodeData(einvoice);

        int updated = einvoiceMapper.updateStatusAndQrCode(tenantId, einvoicePid, "issued", qrCodeData);
        if (updated == 0) {
            throw new IllegalStateException("Failed to update e-invoice status");
        }

        log.info("E-Invoice issued: pid={}, qrCode={}", einvoicePid, qrCodeData);
        return qrCodeData;
    }

    /**
     * Build Golden Tax XML format for an e-invoice.
     * Phase 1: Generates a simplified XML structure. Full Baiwang/Aisino integration is Phase 2.
     *
     * @param einvoicePid the e-invoice PID
     * @return XML string
     */
    public String buildInvoiceXml(String einvoicePid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, Object> einvoice = einvoiceMapper.findByPid(tenantId, einvoicePid);

        if (einvoice == null) {
            throw new IllegalArgumentException("E-Invoice not found: " + einvoicePid);
        }

        List<Map<String, Object>> lines = einvoiceMapper.findLinesByEInvoicePid(tenantId, einvoicePid);

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<Fpxx>\n");
        xml.append("  <Fphm>").append(safeGet(einvoice, "tax_ei_einvoice_no")).append("</Fphm>\n");
        xml.append("  <Fpdm>").append(safeGet(einvoice, "tax_ei_einvoice_code")).append("</Fpdm>\n");
        xml.append("  <Kprq>").append(safeGet(einvoice, "tax_ei_issue_date")).append("</Kprq>\n");
        xml.append("  <Fplx>").append(safeGet(einvoice, "tax_ei_invoice_type")).append("</Fplx>\n");

        // Buyer info
        xml.append("  <Gfxx>\n");
        xml.append("    <Gfmc>").append(safeGet(einvoice, "tax_ei_buyer_name")).append("</Gfmc>\n");
        xml.append("    <Gfsh>").append(safeGet(einvoice, "tax_ei_buyer_uscc")).append("</Gfsh>\n");
        xml.append("    <Gfdzdh>").append(safeGet(einvoice, "tax_ei_buyer_address")).append("</Gfdzdh>\n");
        xml.append("    <Gfyhzh>").append(safeGet(einvoice, "tax_ei_buyer_bank")).append("</Gfyhzh>\n");
        xml.append("  </Gfxx>\n");

        // Seller info
        xml.append("  <Xfxx>\n");
        xml.append("    <Xfmc>").append(safeGet(einvoice, "tax_ei_seller_name")).append("</Xfmc>\n");
        xml.append("    <Xfsh>").append(safeGet(einvoice, "tax_ei_seller_uscc")).append("</Xfsh>\n");
        xml.append("    <Xfdzdh>").append(safeGet(einvoice, "tax_ei_seller_address")).append("</Xfdzdh>\n");
        xml.append("    <Xfyhzh>").append(safeGet(einvoice, "tax_ei_seller_bank")).append("</Xfyhzh>\n");
        xml.append("  </Xfxx>\n");

        // Amounts
        xml.append("  <Hjje>").append(safeGet(einvoice, "tax_ei_subtotal_amount")).append("</Hjje>\n");
        xml.append("  <Hjse>").append(safeGet(einvoice, "tax_ei_tax_total_amount")).append("</Hjse>\n");
        xml.append("  <Jshj>").append(safeGet(einvoice, "tax_ei_total_amount")).append("</Jshj>\n");

        // Lines
        xml.append("  <Spxx>\n");
        for (Map<String, Object> line : lines) {
            xml.append("    <Spmx>\n");
            xml.append("      <Spmc>").append(safeGet(line, "tax_eil_item_name")).append("</Spmc>\n");
            xml.append("      <Spbm>").append(safeGet(line, "tax_eil_item_code")).append("</Spbm>\n");
            xml.append("      <Ggxh>").append(safeGet(line, "tax_eil_specification")).append("</Ggxh>\n");
            xml.append("      <Dw>").append(safeGet(line, "tax_eil_unit")).append("</Dw>\n");
            xml.append("      <Sl>").append(safeGet(line, "tax_eil_quantity")).append("</Sl>\n");
            xml.append("      <Dj>").append(safeGet(line, "tax_eil_unit_price")).append("</Dj>\n");
            xml.append("      <Je>").append(safeGet(line, "tax_eil_amount")).append("</Je>\n");
            xml.append("      <Slv>").append(safeGet(line, "tax_eil_vat_rate_pct")).append("</Slv>\n");
            xml.append("      <Se>").append(safeGet(line, "tax_eil_vat_amount")).append("</Se>\n");
            xml.append("    </Spmx>\n");
        }
        xml.append("  </Spxx>\n");

        xml.append("  <Bz>").append(safeGet(einvoice, "tax_ei_remarks")).append("</Bz>\n");
        xml.append("</Fpxx>");

        log.info("Built Golden Tax XML for e-invoice: pid={}, lineCount={}", einvoicePid, lines.size());
        return xml.toString();
    }

    /**
     * Build preview HTML for an e-invoice (simplified preview).
     *
     * @param einvoicePid the e-invoice PID
     * @return HTML string
     */
    public String buildPreviewHtml(String einvoicePid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, Object> einvoice = einvoiceMapper.findByPid(tenantId, einvoicePid);

        if (einvoice == null) {
            throw new IllegalArgumentException("E-Invoice not found: " + einvoicePid);
        }

        List<Map<String, Object>> lines = einvoiceMapper.findLinesByEInvoicePid(tenantId, einvoicePid);

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        html.append("<style>body{font-family:SimSun,serif;max-width:800px;margin:auto;padding:20px}");
        html.append("table{width:100%;border-collapse:collapse}td,th{border:1px solid #000;padding:4px;font-size:12px}");
        html.append(".title{text-align:center;font-size:20px;font-weight:bold;color:#c00}");
        html.append(".info{margin:10px 0}.amount{text-align:right}</style></head><body>");

        // Title
        String invoiceType = (String) einvoice.get("tax_ei_invoice_type");
        String typeLabel = "special_vat".equals(invoiceType) ? "VAT Special Invoice" : "VAT Invoice";
        html.append("<div class='title'>").append(typeLabel).append("</div>");
        html.append("<div style='text-align:center'>No: ").append(safeGet(einvoice, "tax_ei_einvoice_no"));
        html.append(" / Code: ").append(safeGet(einvoice, "tax_ei_einvoice_code")).append("</div>");

        // Buyer info
        html.append("<div class='info'><b>Buyer:</b> ").append(safeGet(einvoice, "tax_ei_buyer_name"));
        html.append(" | USCC: ").append(safeGet(einvoice, "tax_ei_buyer_uscc")).append("</div>");

        // Lines table
        html.append("<table><tr><th>Item</th><th>Code</th><th>Spec</th><th>Unit</th>");
        html.append("<th>Qty</th><th>Price</th><th>Amount</th><th>Rate%</th><th>Tax</th></tr>");
        for (Map<String, Object> line : lines) {
            html.append("<tr>");
            html.append("<td>").append(safeGet(line, "tax_eil_item_name")).append("</td>");
            html.append("<td>").append(safeGet(line, "tax_eil_item_code")).append("</td>");
            html.append("<td>").append(safeGet(line, "tax_eil_specification")).append("</td>");
            html.append("<td>").append(safeGet(line, "tax_eil_unit")).append("</td>");
            html.append("<td class='amount'>").append(safeGet(line, "tax_eil_quantity")).append("</td>");
            html.append("<td class='amount'>").append(safeGet(line, "tax_eil_unit_price")).append("</td>");
            html.append("<td class='amount'>").append(safeGet(line, "tax_eil_amount")).append("</td>");
            html.append("<td class='amount'>").append(safeGet(line, "tax_eil_vat_rate_pct")).append("</td>");
            html.append("<td class='amount'>").append(safeGet(line, "tax_eil_vat_amount")).append("</td>");
            html.append("</tr>");
        }
        html.append("</table>");

        // Totals
        html.append("<div class='info'><b>Subtotal:</b> ").append(safeGet(einvoice, "tax_ei_subtotal_amount"));
        html.append(" | <b>Tax:</b> ").append(safeGet(einvoice, "tax_ei_tax_total_amount"));
        html.append(" | <b>Total:</b> ").append(safeGet(einvoice, "tax_ei_total_amount")).append("</div>");

        // Seller info
        html.append("<div class='info'><b>Seller:</b> ").append(safeGet(einvoice, "tax_ei_seller_name"));
        html.append(" | USCC: ").append(safeGet(einvoice, "tax_ei_seller_uscc")).append("</div>");

        html.append("</body></html>");
        return html.toString();
    }

    private String buildQrCodeData(Map<String, Object> einvoice) {
        // QR code data format: invoiceCode,invoiceNo,amount,issueDate,sellerUscc
        return String.join(",",
                safeGet(einvoice, "tax_ei_einvoice_code"),
                safeGet(einvoice, "tax_ei_einvoice_no"),
                safeGet(einvoice, "tax_ei_total_amount"),
                LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE),
                safeGet(einvoice, "tax_ei_seller_uscc")
        );
    }

    private String safeGet(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }
}
