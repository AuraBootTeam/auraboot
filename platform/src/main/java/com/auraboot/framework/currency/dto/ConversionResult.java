package com.auraboot.framework.currency.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Result DTO for a currency conversion operation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversionResult {

    private BigDecimal originalAmount;
    private String fromCurrency;
    private BigDecimal convertedAmount;
    private String toCurrency;
    private BigDecimal rateUsed;
    private LocalDate rateDate;
    private boolean triangulated;
}
