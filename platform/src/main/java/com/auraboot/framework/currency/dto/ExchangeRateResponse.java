package com.auraboot.framework.currency.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;

/**
 * Response DTO for exchange rate data.
 */
@Data
public class ExchangeRateResponse {

    private String pid;
    private String baseCurrency;
    private String targetCurrency;
    private BigDecimal rate;
    private LocalDate effectiveDate;
    private String source;
    private Instant createdAt;
    private Instant updatedAt;
}
