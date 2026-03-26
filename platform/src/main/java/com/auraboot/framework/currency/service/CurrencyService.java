package com.auraboot.framework.currency.service;

import com.auraboot.framework.currency.dto.ConversionResult;
import com.auraboot.framework.currency.dto.ExchangeRateRequest;
import com.auraboot.framework.currency.dto.ExchangeRateResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Service for multi-currency operations: rate management and conversion.
 */
public interface CurrencyService {

    /**
     * Convert an amount from one currency to another using rates effective on a given date.
     * Supports triangulated conversion via a pivot currency (USD) if direct rate is unavailable.
     */
    ConversionResult convert(BigDecimal amount, String fromCurrency, String toCurrency, LocalDate date, Long tenantId);

    /**
     * Get the latest exchange rate for a currency pair.
     */
    ExchangeRateResponse getLatestRate(String baseCurrency, String targetCurrency, Long tenantId);

    /**
     * Create or update an exchange rate entry.
     */
    ExchangeRateResponse saveRate(ExchangeRateRequest request, Long tenantId, Long userId);

    /**
     * Delete an exchange rate by PID.
     */
    void deleteRate(String pid, Long tenantId);

    /**
     * List all exchange rates for the tenant, optionally filtered by base currency.
     */
    List<ExchangeRateResponse> listRates(Long tenantId, String baseCurrency, LocalDate date);

    /**
     * Get all latest rates (most recent per pair).
     */
    List<ExchangeRateResponse> listLatestRates(Long tenantId);

    /**
     * Get supported ISO 4217 currency codes.
     */
    List<String> getSupportedCurrencies();
}
