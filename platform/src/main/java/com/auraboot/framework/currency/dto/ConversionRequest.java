package com.auraboot.framework.currency.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request DTO for currency conversion.
 */
@Data
public class ConversionRequest {

    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0", message = "Amount must be non-negative")
    private BigDecimal amount;

    @NotBlank(message = "Source currency is required")
    @Size(min = 3, max = 3)
    private String fromCurrency;

    @NotBlank(message = "Target currency is required")
    @Size(min = 3, max = 3)
    private String toCurrency;

    /** Optional: convert using rate from a specific date. Defaults to today. */
    private LocalDate date;
}
