package com.auraboot.framework.currency.service;

import com.auraboot.framework.currency.spi.CurrencyConversionSpi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Post-processes query result rows to add reporting-currency columns.
 *
 * <p>When a caller specifies a {@code reportingCurrency} (e.g. "usd"), this service
 * scans each result row for fields ending in {@code _base} (already converted to the
 * tenant's functional/base currency) and appends a sibling field ending in
 * {@code _reporting} with the value converted to the requested reporting currency.
 *
 * <p>Design decisions:
 * <ul>
 *   <li>Original {@code _base} fields are <b>never modified</b>; only additive {@code _reporting}
 *       fields are written.</li>
 *   <li>If no rate is found (e.g. finance data not configured), the {@code _reporting} field
 *       is silently omitted — no exception is thrown.</li>
 *   <li>Rate is fetched once per invocation (same date, same pair) and reused across all rows.</li>
 *   <li>When {@code reportingCurrency} equals the tenant base currency the conversion is a no-op
 *       and no extra fields are added.</li>
 * </ul>
 *
 * @author AuraBoot Team
 * @since 6.5.0
 */
@Slf4j
@Service
@ConditionalOnBean(CurrencyConversionSpi.class)
@RequiredArgsConstructor
public class ReportingCurrencyConverter {

    private final CurrencyConversionSpi currencyConversionService;

    private static final int REPORTING_SCALE = 2;

    /**
     * Adds {@code _reporting} sibling fields to every row for each {@code _base} money field.
     *
     * @param rows              mutable query result (List of row maps)
     * @param reportingCurrency target reporting currency code (e.g. "usd"); {@code null} or blank = no-op
     * @param baseCurrency      tenant functional/base currency code (e.g. "cny")
     */
    public void convert(List<Map<String, Object>> rows, String reportingCurrency, String baseCurrency) {
        if (reportingCurrency == null || reportingCurrency.isBlank()) {
            return;
        }
        if (rows == null || rows.isEmpty()) {
            return;
        }
        if (reportingCurrency.equalsIgnoreCase(baseCurrency)) {
            // Same currency: _reporting would equal _base, adding no value.
            return;
        }

        BigDecimal rate = getRateOrNull(baseCurrency, reportingCurrency);
        if (rate == null) {
            log.warn("ReportingCurrencyConverter: no exchange rate found for {}→{}, skipping conversion",
                    baseCurrency, reportingCurrency);
            return;
        }

        for (Map<String, Object> row : rows) {
            // Collect _base field names first to avoid ConcurrentModificationException.
            List<String> baseFields = row.keySet().stream()
                    .filter(k -> k.endsWith("_base"))
                    .toList();

            for (String field : baseFields) {
                Object value = row.get(field);
                if (value instanceof Number n) {
                    BigDecimal amount = new BigDecimal(n.toString());
                    BigDecimal converted = amount.multiply(rate)
                            .setScale(REPORTING_SCALE, RoundingMode.HALF_UP);
                    String reportingField = field.replaceFirst("_base$", "_reporting");
                    row.put(reportingField, converted);
                }
            }
        }
    }

    /**
     * Fetches the exchange rate for the given currency pair using today's date.
     * Returns {@code null} if no rate is available (instead of propagating the exception).
     */
    private BigDecimal getRateOrNull(String from, String to) {
        try {
            var result = currencyConversionService.getRate(from, to, LocalDate.now(), "spot");
            return result != null ? result.getRate() : null;
        } catch (Exception e) {
            log.debug("ReportingCurrencyConverter: rate lookup failed for {}→{}: {}", from, to, e.getMessage());
            return null;
        }
    }
}
