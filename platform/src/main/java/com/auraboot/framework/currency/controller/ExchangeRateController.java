package com.auraboot.framework.currency.controller;

import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.currency.dto.*;
import com.auraboot.framework.currency.service.CurrencyService;
import com.auraboot.framework.currency.service.TimezoneService;
import com.auraboot.framework.currency.service.impl.EcbRateFetcher;
import com.auraboot.framework.tenant.service.TenantPreferenceService;
import com.auraboot.framework.currency.spi.CurrencyConversionSpi;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for exchange rate management and currency conversion.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/exchange-rates")
@RequiredArgsConstructor
public class ExchangeRateController {

    private final CurrencyService currencyService;
    private final TimezoneService timezoneService;
    private final Optional<CurrencyConversionSpi> currencyConversionService;
    private final TenantPreferenceService tenantPreferenceService;

    /** Injected as Optional because the bean may be absent when ecb.enabled=false */
    private final Optional<EcbRateFetcher> ecbRateFetcher;

    // ==================== Base Currency ====================

    /**
     * Get the effective base (functional) currency for the current tenant.
     * Applies the 3-tier priority: preference → fin_currency table → CNY.
     * GET /api/admin/exchange-rates/base-currency
     */
    @GetMapping("/base-currency")
    public ResponseEntity<Map<String, String>> getBaseCurrency() {
        String base = currencyConversionService.map(CurrencyConversionSpi::getBaseCurrency).orElse("CNY");
        return ResponseEntity.ok(Map.of("baseCurrency", base));
    }

    /**
     * Set the tenant base currency preference.
     * Persists the value under key {@code currency.base} in {@code ab_tenant_preference}.
     * PUT /api/admin/exchange-rates/base-currency
     */
    @PutMapping("/base-currency")
    public ResponseEntity<Void> setBaseCurrency(@RequestBody Map<String, String> body) {
        String currency = body.get("baseCurrency");
        if (currency == null || currency.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        tenantPreferenceService.setPreference(tenantId, "currency.base",
                JsonNodeFactory.instance.textNode(currency.toUpperCase()));
        return ResponseEntity.ok().build();
    }

    // ==================== Exchange Rate CRUD ====================

    /**
     * List exchange rates with optional filters.
     */
    @GetMapping
    public ApiResponse<List<ExchangeRateResponse>> listRates(
            @RequestParam(required = false) String baseCurrency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<ExchangeRateResponse> rates = currencyService.listRates(tenantId, baseCurrency, date);
        return ApiResponse.success(rates);
    }

    /**
     * Get all latest rates (most recent per currency pair).
     */
    @GetMapping("/latest")
    public ApiResponse<List<ExchangeRateResponse>> listLatestRates() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(currencyService.listLatestRates(tenantId));
    }

    /**
     * Get the latest rate for a specific currency pair.
     */
    @GetMapping("/rate")
    public ApiResponse<ExchangeRateResponse> getRate(
            @RequestParam String baseCurrency,
            @RequestParam String targetCurrency) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(currencyService.getLatestRate(baseCurrency, targetCurrency, tenantId));
    }

    /**
     * Create or update an exchange rate.
     */
    @PostMapping
    public ApiResponse<ExchangeRateResponse> saveRate(
            @Valid @RequestBody ExchangeRateRequest request,
            @CurrentUserId Long userId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(currencyService.saveRate(request, tenantId, userId));
    }

    /**
     * Delete an exchange rate by PID.
     */
    @DeleteMapping("/{pid}")
    public ApiResponse<String> deleteRate(@PathVariable String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        currencyService.deleteRate(pid, tenantId);
        return ApiResponse.success("Deleted");
    }

    // ==================== Currency Conversion ====================

    /**
     * Convert an amount between currencies.
     * GET /api/admin/exchange-rates/convert?amount=100&from=USD&to=CNY&date=2026-03-18
     */
    @GetMapping("/convert")
    public ApiResponse<ConversionResult> convert(
            @RequestParam BigDecimal amount,
            @RequestParam("from") String fromCurrency,
            @RequestParam("to") String toCurrency,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long tenantId = MetaContext.getCurrentTenantId();
        ConversionResult result = currencyService.convert(amount, fromCurrency, toCurrency, date, tenantId);
        return ApiResponse.success(result);
    }

    /**
     * List supported ISO 4217 currency codes.
     */
    @GetMapping("/currencies")
    public ApiResponse<List<String>> getSupportedCurrencies() {
        return ApiResponse.success(currencyService.getSupportedCurrencies());
    }

    // ==================== ECB Sync ====================

    /**
     * Manually trigger an ECB exchange rate sync.
     * POST /api/admin/exchange-rates/sync-ecb
     *
     * <p>Returns {@code {"status":"disabled"}} when {@code currency.ecb.enabled=false}.
     * Returns {@code {"status":"ok", "saved": N}} on success.
     */
    @PostMapping("/sync-ecb")
    public ResponseEntity<Map<String, Object>> syncEcb() {
        if (ecbRateFetcher.isEmpty()) {
            log.info("ECB sync requested but EcbRateFetcher is disabled (currency.ecb.enabled=false)");
            return ResponseEntity.ok(Map.of(
                    "status", "disabled",
                    "message", "ECB rate fetcher is disabled. Set currency.ecb.enabled=true to enable."
            ));
        }

        try {
            int saved = ecbRateFetcher.get().fetchAndSave();
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "saved", saved
            ));
        } catch (EcbRateFetcher.EcbFetchException e) {
            log.error("Manual ECB sync failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", e.getMessage()
            ));
        }
    }

    // ==================== Timezone ====================

    /**
     * List all supported IANA timezones with UTC offset info.
     */
    @GetMapping("/timezones")
    public ApiResponse<List<TimezoneInfo>> listTimezones() {
        return ApiResponse.success(timezoneService.listTimezones());
    }

    /**
     * Get info for a specific timezone.
     */
    @GetMapping("/timezones/{timezoneId}")
    public ApiResponse<TimezoneInfo> getTimezoneInfo(@PathVariable String timezoneId) {
        return ApiResponse.success(timezoneService.getTimezoneInfo(timezoneId));
    }
}
