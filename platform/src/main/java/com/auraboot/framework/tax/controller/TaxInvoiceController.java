package com.auraboot.framework.tax.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.tax.dto.EInvoiceGenerateRequest;
import com.auraboot.framework.tax.dto.VatCalculationResult;
import com.auraboot.framework.tax.service.TaxInvoiceService;
import com.auraboot.framework.tax.service.VatCalculationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Tax Invoice Controller.
 * Provides endpoints for e-invoice generation, issuance, XML export, and preview.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Slf4j
@RestController
@RequestMapping("/api/tax")
@RequiredArgsConstructor
@Validated
@Tag(name = "Tax / E-Invoice", description = "China tax compliance: e-invoice generation, VAT calculation")
public class TaxInvoiceController {

    private final TaxInvoiceService taxInvoiceService;
    private final VatCalculationService vatCalculationService;

    /**
     * Generate e-invoice data from a source document or manual input.
     */
    @PostMapping("/einvoice/generate")
    @Operation(summary = "Generate E-Invoice", description = "Generate e-invoice data from a source (AR/SO/manual)")
    @RequirePermission("tax.einvoice.manage")
    public ApiResponse<Map<String, Object>> generateEInvoice(
            @Valid @RequestBody EInvoiceGenerateRequest request) {

        log.info("Generate e-invoice: sourceType={}, sourceId={}", request.getSourceType(), request.getSourceId());
        Map<String, Object> result = taxInvoiceService.generateEInvoice(request);
        return ApiResponse.success(result);
    }

    /**
     * Issue an e-invoice (DRAFT -> ISSUED).
     */
    @PostMapping("/einvoice/{id}/issue")
    @Operation(summary = "Issue E-Invoice", description = "Issue a draft e-invoice, generate QR code")
    @RequirePermission("tax.einvoice.manage")
    public ApiResponse<Map<String, String>> issueEInvoice(@PathVariable String id) {

        log.info("Issue e-invoice: pid={}", id);
        String qrCode = taxInvoiceService.issueEInvoice(id);
        return ApiResponse.success(Map.of("qrCodeData", qrCode, "status", "issued"));
    }

    /**
     * Download the Golden Tax XML format for an e-invoice.
     */
    @GetMapping("/einvoice/{id}/xml")
    @Operation(summary = "Download XML", description = "Download Golden Tax XML format for an e-invoice")
    @RequirePermission("tax.einvoice.read")
    public void downloadXml(@PathVariable String id, HttpServletResponse response) throws IOException {

        log.info("Download e-invoice XML: pid={}", id);
        String xml = taxInvoiceService.buildInvoiceXml(id);

        response.setContentType("application/xml; charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"einvoice_" + id + ".xml\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");

        try (OutputStream os = response.getOutputStream()) {
            os.write(xml.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    /**
     * Preview an e-invoice as HTML.
     */
    @GetMapping("/einvoice/{id}/preview")
    @Operation(summary = "Preview E-Invoice", description = "Preview e-invoice as rendered HTML")
    @RequirePermission("tax.einvoice.read")
    public void previewHtml(@PathVariable String id, HttpServletResponse response) throws IOException {

        log.info("Preview e-invoice: pid={}", id);
        String html = taxInvoiceService.buildPreviewHtml(id);

        response.setContentType("text/html; charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");

        try (OutputStream os = response.getOutputStream()) {
            os.write(html.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    /**
     * Calculate VAT for a given amount and rate code.
     */
    @GetMapping("/vat/calculate")
    @Operation(summary = "Calculate VAT", description = "Calculate VAT amount for a given pre-tax amount and rate code")
    @RequirePermission("tax.vat_rate.read")
    public ApiResponse<VatCalculationResult> calculateVat(
            @RequestParam BigDecimal amount,
            @RequestParam String vatRateCode) {

        VatCalculationResult result = vatCalculationService.calculateVat(amount, vatRateCode);
        return ApiResponse.success(result);
    }
}
