package com.auraboot.framework.tax.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for generating an e-invoice from a source document.
 */
@Data
public class EInvoiceGenerateRequest {

    @NotBlank
    private String sourceType; // AR_INVOICE, SALES_ORDER, MANUAL

    private String sourceId;

    @NotBlank
    private String invoiceType; // NORMAL, SPECIAL_VAT, ELECTRONIC, SIMPLIFIED

    @NotBlank
    private String buyerName;

    private String buyerUscc;
    private String buyerAddress;
    private String buyerBank;

    private String sellerName;
    private String sellerUscc;
    private String sellerAddress;
    private String sellerBank;

    private String currencyCode;
    private String remarks;
}
