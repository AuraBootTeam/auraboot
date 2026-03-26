package com.auraboot.framework.tax.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Result of a VAT calculation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VatCalculationResult {

    private BigDecimal amount;
    private BigDecimal vatRate;
    private BigDecimal vatAmount;
    private BigDecimal totalAmount;
    private String vatRateCode;
}
