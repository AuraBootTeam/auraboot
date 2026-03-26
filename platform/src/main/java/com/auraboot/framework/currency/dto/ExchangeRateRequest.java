package com.auraboot.framework.currency.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request DTO for creating or updating an exchange rate.
 */
@Data
public class ExchangeRateRequest {

    @NotBlank(message = "Base currency is required")
    @Size(min = 3, max = 3, message = "Currency code must be exactly 3 characters (ISO 4217)")
    private String baseCurrency;

    @NotBlank(message = "Target currency is required")
    @Size(min = 3, max = 3, message = "Currency code must be exactly 3 characters (ISO 4217)")
    private String targetCurrency;

    @NotNull(message = "Rate is required")
    @DecimalMin(value = "0.00000001", message = "Rate must be positive")
    private BigDecimal rate;

    @NotNull(message = "Effective date is required")
    private LocalDate effectiveDate;

    private String source = "manual";
}
