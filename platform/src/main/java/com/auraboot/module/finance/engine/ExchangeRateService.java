package com.auraboot.module.finance.engine;

import com.auraboot.module.meta.bitemporal.BitemporalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;

/**
 * Service for managing exchange rates with bitemporal storage.
 *
 * <p>Exchange rates are stored in the {@code fac_exchange_rate} bitemporal table,
 * allowing full audit history and point-in-time queries for any currency pair.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    private static final String TABLE = "fac_exchange_rate";

    private final BitemporalRepository bitemporalRepository;

    /**
     * Set (or correct) the exchange rate for a currency pair within a validity period.
     *
     * @param sourceCurrency source currency code (e.g., "usd")
     * @param targetCurrency target currency code (e.g., "cny")
     * @param rate           the exchange rate
     * @param validFrom      start of the validity period (inclusive)
     * @param validTo        end of the validity period (exclusive)
     * @param tenantId       tenant ID
     * @return the new row ID
     */
    public Long setRate(String sourceCurrency, String targetCurrency,
                        BigDecimal rate, LocalDate validFrom, LocalDate validTo, Long tenantId) {
        long entityKey = computeEntityKey(sourceCurrency, targetCurrency);

        Map<String, Object> correctedData = new HashMap<>();
        correctedData.put("source_currency", normalizeCurrency(sourceCurrency));
        correctedData.put("target_currency", normalizeCurrency(targetCurrency));
        correctedData.put("rate", rate);
        correctedData.put("valid_from", validFrom);
        correctedData.put("valid_to", validTo);

        Long newId = bitemporalRepository.correct(TABLE, entityKey, correctedData, tenantId);
        log.info("Set exchange rate {}→{} = {} (valid {}~{}, entityKey={}, newId={})",
                normalizeCurrency(sourceCurrency), normalizeCurrency(targetCurrency),
                rate, validFrom, validTo, entityKey, newId);
        return newId;
    }

    /**
     * Get the current exchange rate for a currency pair.
     *
     * @param sourceCurrency source currency code
     * @param targetCurrency target currency code
     * @param tenantId       tenant ID
     * @return the current rate, or null if not found
     */
    public BigDecimal getRate(String sourceCurrency, String targetCurrency, Long tenantId) {
        long entityKey = computeEntityKey(sourceCurrency, targetCurrency);
        Map<String, Object> row = bitemporalRepository.findCurrent(TABLE, entityKey, tenantId);
        if (row == null) {
            return null;
        }
        return toBigDecimal(row.get("rate"));
    }

    /**
     * Get the exchange rate as of a specific valid date and system date.
     *
     * @param sourceCurrency source currency code
     * @param targetCurrency target currency code
     * @param validDate      the date in valid-time to query
     * @param systemDate     the instant in transaction-time to query
     * @param tenantId       tenant ID
     * @return the rate as of the given dates, or null if not found
     */
    public BigDecimal getRateAsOf(String sourceCurrency, String targetCurrency,
                                   LocalDate validDate, Instant systemDate, Long tenantId) {
        long entityKey = computeEntityKey(sourceCurrency, targetCurrency);
        Map<String, Object> row = bitemporalRepository.findAsOf(TABLE, entityKey, validDate, systemDate, tenantId);
        if (row == null) {
            return null;
        }
        return toBigDecimal(row.get("rate"));
    }

    /**
     * Get the full history of exchange rate changes for a currency pair.
     *
     * @param sourceCurrency source currency code
     * @param targetCurrency target currency code
     * @param tenantId       tenant ID
     * @return list of all versions, ordered by transaction time
     */
    public List<Map<String, Object>> getHistory(String sourceCurrency, String targetCurrency, Long tenantId) {
        long entityKey = computeEntityKey(sourceCurrency, targetCurrency);
        return bitemporalRepository.findHistory(TABLE, entityKey, tenantId);
    }

    /**
     * Compute a stable entity key for a currency pair.
     * The key is case-insensitive: "usd/cny" and "USD/CNY" produce the same key.
     */
    private long computeEntityKey(String source, String target) {
        return (long) Objects.hash(normalizeCurrency(source), normalizeCurrency(target));
    }

    private static String normalizeCurrency(String currency) {
        return currency == null ? null : currency.toLowerCase(Locale.ROOT);
    }

    /**
     * Safely convert a value from the result map to BigDecimal.
     */
    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        return new BigDecimal(value.toString());
    }
}
