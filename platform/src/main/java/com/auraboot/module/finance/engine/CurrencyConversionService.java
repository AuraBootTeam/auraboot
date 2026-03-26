package com.auraboot.module.finance.engine;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.framework.currency.service.CurrencyService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.tenant.service.TenantPreferenceService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Currency conversion engine for multi-currency support.
 *
 * <p>Queries the DSL-managed {@code mt_fin_exchange_rate} table
 * with a 5-step fallback chain:
 * <ol>
 *   <li>Exact match (from, to, date, type)</li>
 *   <li>Nearest earlier date</li>
 *   <li>Inverse rate (1/rate)</li>
 *   <li>Triangulation via pivot currency</li>
 *   <li>Platform-layer fallback ({@code ab_exchange_rate} via {@link com.auraboot.framework.currency.service.CurrencyService})</li>
 * </ol>
 *
 * @author AuraBoot Team
 * @since 6.4.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CurrencyConversionService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantClock tenantClock;
    private final CurrencyService currencyService;
    private final TenantPreferenceService tenantPreferenceService;

    private static final String RATE_TABLE = "mt_fin_exchange_rate";
    private static final String CURRENCY_TABLE = "mt_fin_currency";

    private static final int DEFAULT_RATE_SCALE = 6;
    private static final int DEFAULT_AMOUNT_SCALE = 2;

    @Value("${currency.pivot:usd}")
    private String pivotCurrency;

    @Value("${currency.max-rate-age-days:30}")
    private int maxRateAgeDays;

    // ==================== Public API ====================

    /**
     * Get the exchange rate for a currency pair on a given date.
     * Uses the 5-step fallback chain.
     *
     * @param fromCurrency source currency code (e.g. "usd")
     * @param toCurrency   target currency code (e.g. "cny")
     * @param date         effective date
     * @param rateType     rate type: "spot", "accounting", "tax"
     * @return exchange rate result with rate value and source record ID
     * @throws BusinessException if no rate can be found
     */
    public ExchangeRateResult getRate(String fromCurrency, String toCurrency,
                                       LocalDate date, String rateType) {
        return getRate(fromCurrency, toCurrency, date, rateType, MetaContext.getCurrentTenantId());
    }

    public ExchangeRateResult getRate(String fromCurrency, String toCurrency,
                                       LocalDate date, String rateType, Long tenantId) {
        if (fromCurrency.equalsIgnoreCase(toCurrency)) {
            return ExchangeRateResult.identity();
        }

        if (rateType == null) {
            rateType = "spot";
        }
        String normalizedFromCurrency = normalizeCurrency(fromCurrency);
        String normalizedToCurrency = normalizeCurrency(toCurrency);
        String normalizedRateType = normalizeCurrency(rateType);
        String normalizedPivotCurrency = normalizeCurrency(pivotCurrency);

        // Step 1: Exact match
        ExchangeRateResult result = queryRate(normalizedFromCurrency, normalizedToCurrency, date, normalizedRateType, tenantId, true);
        if (result != null) return result;

        // Step 2: Nearest earlier date (within max age)
        result = queryRate(normalizedFromCurrency, normalizedToCurrency, date, normalizedRateType, tenantId, false);
        if (result != null) return result;

        // Step 3: Inverse rate
        ExchangeRateResult inverse = queryRate(normalizedToCurrency, normalizedFromCurrency, date, normalizedRateType, tenantId, false);
        if (inverse != null) {
            BigDecimal inverseRate = BigDecimal.ONE.divide(inverse.getRate(), DEFAULT_RATE_SCALE, RoundingMode.HALF_UP);
            return new ExchangeRateResult(inverseRate, inverse.getRateId(), true);
        }

        // Step 4: Triangulation via pivot
        if (!normalizedFromCurrency.equalsIgnoreCase(normalizedPivotCurrency) && !normalizedToCurrency.equalsIgnoreCase(normalizedPivotCurrency)) {
            ExchangeRateResult fromToPivot = queryRate(normalizedFromCurrency, normalizedPivotCurrency, date, normalizedRateType, tenantId, false);
            ExchangeRateResult pivotToTarget = queryRate(normalizedPivotCurrency, normalizedToCurrency, date, normalizedRateType, tenantId, false);

            if (fromToPivot == null) {
                ExchangeRateResult pivotToFrom = queryRate(normalizedPivotCurrency, normalizedFromCurrency, date, normalizedRateType, tenantId, false);
                if (pivotToFrom != null) {
                    fromToPivot = new ExchangeRateResult(
                            BigDecimal.ONE.divide(pivotToFrom.getRate(), DEFAULT_RATE_SCALE, RoundingMode.HALF_UP),
                            pivotToFrom.getRateId(), true);
                }
            }
            if (pivotToTarget == null) {
                ExchangeRateResult targetToPivot = queryRate(normalizedToCurrency, normalizedPivotCurrency, date, normalizedRateType, tenantId, false);
                if (targetToPivot != null) {
                    pivotToTarget = new ExchangeRateResult(
                            BigDecimal.ONE.divide(targetToPivot.getRate(), DEFAULT_RATE_SCALE, RoundingMode.HALF_UP),
                            targetToPivot.getRateId(), true);
                }
            }

            if (fromToPivot != null && pivotToTarget != null) {
                BigDecimal triangulatedRate = fromToPivot.getRate().multiply(pivotToTarget.getRate())
                        .setScale(DEFAULT_RATE_SCALE, RoundingMode.HALF_UP);
                return new ExchangeRateResult(triangulatedRate, null, true);
            }
        }

        // Step 5: Platform-level fallback (ab_exchange_rate table)
        log.info("Finance exchange rate not found for {}/{} on {}, trying platform fallback",
                fromCurrency, toCurrency, date);
        try {
            var platformResult = currencyService.convert(
                    BigDecimal.ONE, normalizedFromCurrency, normalizedToCurrency, date, tenantId);
            if (platformResult != null && platformResult.getRateUsed() != null) {
                log.info("Platform-level exchange rate found for {}/{} on {}: {}",
                        fromCurrency, toCurrency, date, platformResult.getRateUsed());
                return new ExchangeRateResult(platformResult.getRateUsed(), null, false);
            }
        } catch (Exception e) {
            log.warn("Platform fallback also failed for {}/{}: {}", fromCurrency, toCurrency, e.getMessage());
        }

        throw new BusinessException(
                String.format("exchange_rate_not_found: No exchange rate found for %s→%s on %s (checked finance and platform tables)",
                        normalizedFromCurrency, normalizedToCurrency, date));
    }

    /**
     * Convert an amount from one currency to another.
     *
     * @param amount       the amount to convert
     * @param fromCurrency source currency code
     * @param toCurrency   target currency code
     * @param date         effective date for rate lookup
     * @param rateType     rate type (SPOT/ACCOUNTING/TAX)
     * @return converted amount, rounded per target currency precision
     */
    public BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency,
                              LocalDate date, String rateType) {
        if (amount == null) return null;
        if (fromCurrency.equalsIgnoreCase(toCurrency)) return amount;

        String normalizedToCurrency = normalizeCurrency(toCurrency);
        ExchangeRateResult rateResult = getRate(fromCurrency, toCurrency, date, rateType, MetaContext.getCurrentTenantId());
        int targetScale = getCurrencyScale(normalizedToCurrency);
        RoundingMode roundingMode = getCurrencyRoundingMode(normalizedToCurrency);

        return amount.multiply(rateResult.getRate()).setScale(targetScale, roundingMode);
    }

    /**
     * Batch convert all money fields on a record map.
     * Called from BindingRule scripts via the script engine.
     *
     * @param record          the mutable record map
     * @param amountFields    list of field codes to convert (e.g. ["sl_so_total_amount"])
     * @param currencyField   field code for document currency (e.g. "currency_code")
     * @param rateField       field code for locked rate (e.g. "exchange_rate")
     * @param rateIdField     field code for rate source ID (e.g. "exchange_rate_id")
     * @param baseCurrencyField field code for base currency snapshot (e.g. "base_currency_code")
     */
    public void convertRecord(Map<String, Object> record, List<String> amountFields,
                              String currencyField, String rateField,
                              String rateIdField, String baseCurrencyField) {
        convertRecord(record, amountFields, currencyField, rateField, rateIdField, baseCurrencyField,
                MetaContext.getCurrentTenantId());
    }

    public void convertRecord(Map<String, Object> record, List<String> amountFields,
                              String currencyField, String rateField,
                              String rateIdField, String baseCurrencyField, Long tenantId) {
        String docCurrency = resolveCurrencyCode(record.get(currencyField));
        String baseCurrency = getBaseCurrency(tenantId);
        LocalDate docDate = resolveDate(record);

        record.put(baseCurrencyField, baseCurrency);

        if (docCurrency == null || docCurrency.equalsIgnoreCase(baseCurrency)) {
            // Same currency: rate=1, base amounts = original amounts
            record.put(rateField, BigDecimal.ONE);
            for (String field : amountFields) {
                Object value = record.get(field);
                if (value != null) {
                    record.put(field + "_base", toBigDecimal(value));
                }
            }
            return;
        }

        ExchangeRateResult rateResult = getRate(docCurrency, baseCurrency, docDate, "spot", tenantId);
        record.put(rateField, rateResult.getRate());
        if (rateResult.getRateId() != null) {
            record.put(rateIdField, rateResult.getRateId());
        }

        int baseScale = getCurrencyScale(baseCurrency);
        RoundingMode roundingMode = getCurrencyRoundingMode(baseCurrency);

        for (String field : amountFields) {
            BigDecimal amount = toBigDecimal(record.get(field));
            if (amount != null) {
                BigDecimal baseAmount = amount.multiply(rateResult.getRate())
                        .setScale(baseScale, roundingMode);
                record.put(field + "_base", baseAmount);
            }
        }
    }

    /**
     * Returns the base (functional) currency for the current tenant.
     *
     * <p>Priority chain (highest to lowest):
     * <ol>
     *   <li><b>Tenant preference</b> ({@code ab_tenant_preference} key {@code currency.base}) —
     *       Explicit operator-level override. Takes precedence because an operator who has
     *       deliberately set this preference intends to override any module-level default.</li>
     *   <li><b>Finance currency master</b> ({@code mt_fin_currency} where
     *       {@code fin_cur_is_base = true}) — Business-level default set by the finance
     *       module's currency configuration. Used when no explicit preference exists.</li>
     *   <li><b>Hardcoded fallback</b> {@code "cny"} — Last resort for tenants with no
     *       currency configuration. Logged at WARN level to encourage explicit setup.</li>
     * </ol>
     *
     * @return ISO 4217 currency code in uppercase (e.g. "cny", "usd")
     */
    public String getBaseCurrency() {
        return getBaseCurrency(MetaContext.getCurrentTenantId());
    }

    public String getBaseCurrency(Long tenantId) {

        // Priority 1: tenant preference (currency.base key) — explicit operator override
        try {
            JsonNode node = tenantPreferenceService.getPreference(tenantId, "currency.base");
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                return node.asText().toLowerCase(Locale.ROOT);
            }
        } catch (Exception e) {
            log.warn("Failed to read base currency preference for tenant {}: {}", tenantId, e.getMessage());
        }

        // Priority 2: finance DSL fin_currency table (fin_cur_is_base = true)
        try {
            // Dynamic DSL table — JdbcTemplate required (MyBatis Mapper not applicable for dynamic table names)
            String sql = "SELECT fin_cur_code FROM " + CURRENCY_TABLE
                    + " WHERE tenant_id = ? AND fin_cur_is_base = true"
                    + " LIMIT 1";
            List<String> results = jdbcTemplate.queryForList(sql, String.class, tenantId);
            if (!results.isEmpty() && results.get(0) != null) {
                return results.get(0).toLowerCase(Locale.ROOT);
            }
        } catch (Exception e) {
            log.warn("Failed to query base currency from fin_currency for tenant {}: {}", tenantId, e.getMessage());
        }

        // Priority 3: hardcoded fallback (bootstrapping fallback for new tenants without any currency config)
        log.warn("No base currency configured for tenant {}, falling back to CNY", tenantId);
        return "cny";
    }

    // ==================== Internal Query ====================

    private ExchangeRateResult queryRate(String from, String to, LocalDate date,
                                          String rateType, Long tenantId, boolean exactDate) {
        String sql;
        Object[] params;

        if (exactDate) {
            sql = "SELECT id, fin_exr_rate FROM " + RATE_TABLE
                    + " WHERE tenant_id = ? AND fin_exr_from_currency = ?"
                    + " AND fin_exr_to_currency = ?"
                    + " AND fin_exr_effective_date = ? AND fin_exr_rate_type = ?"
                    + " LIMIT 1";
            params = new Object[]{tenantId, normalizeCurrency(from), normalizeCurrency(to), date, normalizeCurrency(rateType)};
        } else {
            LocalDate minDate = date.minusDays(maxRateAgeDays);
            sql = "SELECT id, fin_exr_rate FROM " + RATE_TABLE
                    + " WHERE tenant_id = ? AND fin_exr_from_currency = ?"
                    + " AND fin_exr_to_currency = ?"
                    + " AND fin_exr_effective_date <= ? AND fin_exr_effective_date >= ?"
                    + " AND fin_exr_rate_type = ?"
                    + " ORDER BY fin_exr_effective_date DESC LIMIT 1";
            params = new Object[]{tenantId, normalizeCurrency(from), normalizeCurrency(to), date, minDate, normalizeCurrency(rateType)};
        }

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                BigDecimal rate = toBigDecimal(row.get("fin_exr_rate"));
                Long rateId = row.get("id") != null ? ((Number) row.get("id")).longValue() : null;
                if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
                    return new ExchangeRateResult(rate, rateId, false);
                }
            }
        } catch (Exception e) {
            log.debug("Exchange rate query failed for {}→{}: {}", from, to, e.getMessage());
        }
        return null;
    }

    private int getCurrencyScale(String currencyCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        try {
            String sql = "SELECT fin_cur_decimal_places FROM " + CURRENCY_TABLE
                    + " WHERE fin_cur_code = ? AND tenant_id = ? LIMIT 1";
            List<Integer> results = jdbcTemplate.queryForList(sql, Integer.class, currencyCode, tenantId);
            if (!results.isEmpty() && results.get(0) != null) {
                return results.get(0);
            }
        } catch (Exception e) {
            log.debug("Failed to get decimal places for {}: {}", currencyCode, e.getMessage());
        }
        return DEFAULT_AMOUNT_SCALE;
    }

    private RoundingMode getCurrencyRoundingMode(String currencyCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        try {
            String sql = "SELECT fin_cur_rounding_mode FROM " + CURRENCY_TABLE
                    + " WHERE fin_cur_code = ? AND tenant_id = ? LIMIT 1";
            List<String> results = jdbcTemplate.queryForList(sql, String.class, currencyCode, tenantId);
            if (!results.isEmpty() && "half_even".equals(results.get(0))) {
                return RoundingMode.HALF_EVEN;
            }
        } catch (Exception e) {
            log.debug("Failed to get rounding mode for {}: {}", currencyCode, e.getMessage());
        }
        return RoundingMode.HALF_UP;
    }

    // ==================== Helpers ====================

    private String resolveCurrencyCode(Object ref) {
        if (ref == null) return null;
        if (ref instanceof String s) return normalizeCurrency(s);
        // Reference field stores ID — need to look up the code
        Long tenantId = MetaContext.getCurrentTenantId();
        try {
            String sql = "SELECT fin_cur_code FROM " + CURRENCY_TABLE
                    + " WHERE id = ? AND tenant_id = ? LIMIT 1";
            List<String> results = jdbcTemplate.queryForList(sql, String.class,
                    ((Number) ref).longValue(), tenantId);
            return results.isEmpty() ? null : normalizeCurrency(results.get(0));
        } catch (Exception e) {
            return normalizeCurrency(ref.toString());
        }
    }

    private LocalDate resolveDate(Map<String, Object> record) {
        // Try common date field names
        for (String dateField : List.of("document_date", "order_date", "entry_date",
                "created_at", "create_time")) {
            Object val = record.get(dateField);
            if (val instanceof LocalDate ld) return ld;
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            return LocalDate.now();
        }
        return tenantClock.businessDate(tenantId);
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeCurrency(String currency) {
        return currency == null ? null : currency.toLowerCase(Locale.ROOT);
    }

    // ==================== Result DTO ====================

    /**
     * Exchange rate lookup result.
     */
    public record ExchangeRateResult(BigDecimal rate, Long rateId, boolean derived) {
        /**
         * Identity result: rate=1, no source record, for same-currency conversions.
         */
        public static ExchangeRateResult identity() {
            return new ExchangeRateResult(BigDecimal.ONE, null, false);
        }

        public BigDecimal getRate() { return rate; }
        public Long getRateId() { return rateId; }
    }
}
