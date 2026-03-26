package com.auraboot.module.finance.engine;

import com.auraboot.framework.currency.dao.entity.ExchangeRate;
import com.auraboot.framework.currency.dao.mapper.ExchangeRateMapper;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for CurrencyConversionService.convertRecord() as invoked by
 * CurrencyConversionHandler on CRM opportunity CREATE / UPDATE commands.
 *
 * <p>The handler config for crm_opportunity is:
 * <pre>
 * {
 *   "mode": "header",
 *   "currencyField":     "crm_opp_currency_code",
 *   "rateField":         "crm_opp_exchange_rate",
 *   "rateIdField":       "crm_opp_exchange_rate_id",
 *   "baseCurrencyField": "crm_opp_base_currency_code",
 *   "amountFields":      ["crm_opp_expected_amount"]
 * }
 * </pre>
 *
 * <p>These tests verify that {@code convertRecord()} correctly:
 * <ul>
 *   <li>Populates {@code crm_opp_exchange_rate} with the looked-up rate</li>
 *   <li>Calculates {@code crm_opp_expected_amount_base} as amount × rate</li>
 *   <li>Snapshots {@code crm_opp_base_currency_code} (falls back to "cny")</li>
 *   <li>Handles same-currency case (rate=1, base amount = original)</li>
 *   <li>Throws when no exchange rate is available for the pair</li>
 * </ul>
 */
class CurrencyConversionHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CurrencyConversionService currencyConversionService;

    @Autowired
    private ExchangeRateMapper exchangeRateMapper;

    // CRM opportunity field names that match the bindingRules.json config
    private static final String CURRENCY_FIELD      = "crm_opp_currency_code";
    private static final String RATE_FIELD          = "crm_opp_exchange_rate";
    private static final String RATE_ID_FIELD       = "crm_opp_exchange_rate_id";
    private static final String BASE_CURRENCY_FIELD = "crm_opp_base_currency_code";
    private static final String AMOUNT_FIELD        = "crm_opp_expected_amount";

    private static final LocalDate TODAY = LocalDate.now();

    // ---------------------------------------------------------------------------
    // Setup: insert platform-level exchange rates for test isolation.
    // Using ISO 4217 codes unlikely to collide with real mt_fin_* data.
    // ---------------------------------------------------------------------------

    @BeforeEach
    void insertTestRates() {
        // USD → CNY at 7.20 (platform ab_exchange_rate table)
        insertPlatformRate("usd", "cny", new BigDecimal("7.200000"));
        // EUR → CNY at 7.80
        insertPlatformRate("eur", "cny", new BigDecimal("7.800000"));
    }

    // ===========================================================================
    // convertRecord() — CRM opportunity header-mode field mapping
    // ===========================================================================

    /**
     * When crm_opp_currency_code = "usd" and crm_opp_expected_amount = 1000,
     * convertRecord() must populate:
     *   crm_opp_exchange_rate          = 7.20
     *   crm_opp_expected_amount_base   = 7200.00
     *   crm_opp_base_currency_code     = "cny"
     */
    @Test
    void convertRecord_usdAmount_calculatesBaseCnyAmountCorrectly() {
        // Arrange: simulate the payload that CurrencyConversionHandler receives
        Map<String, Object> record = new HashMap<>();
        record.put(CURRENCY_FIELD, "usd");
        record.put(AMOUNT_FIELD, new BigDecimal("1000.00"));

        // Act
        currencyConversionService.convertRecord(
                record,
                List.of(AMOUNT_FIELD),
                CURRENCY_FIELD,
                RATE_FIELD,
                RATE_ID_FIELD,
                BASE_CURRENCY_FIELD
        );

        // Assert: rate must equal the value we inserted (7.20)
        BigDecimal rate = toBigDecimal(record.get(RATE_FIELD));
        assertThat(rate).isNotNull();
        assertThat(rate).isEqualByComparingTo(new BigDecimal("7.200000"));

        // Assert: base amount = 1000 * 7.20 = 7200.00
        BigDecimal baseAmount = toBigDecimal(record.get(AMOUNT_FIELD + "_base"));
        assertThat(baseAmount).isNotNull();
        assertThat(baseAmount).isEqualByComparingTo(new BigDecimal("7200.00"));

        // Assert: base currency snapshot defaults to CNY (no finance DSL configured)
        assertThat(record.get(BASE_CURRENCY_FIELD)).isEqualTo("cny");
    }

    /**
     * When crm_opp_currency_code = "eur" and crm_opp_expected_amount = 500,
     * the base amount must use the EUR→CNY rate (7.80).
     */
    @Test
    void convertRecord_eurAmount_calculatesBaseCnyAmountCorrectly() {
        Map<String, Object> record = new HashMap<>();
        record.put(CURRENCY_FIELD, "eur");
        record.put(AMOUNT_FIELD, new BigDecimal("500.00"));

        currencyConversionService.convertRecord(
                record,
                List.of(AMOUNT_FIELD),
                CURRENCY_FIELD,
                RATE_FIELD,
                RATE_ID_FIELD,
                BASE_CURRENCY_FIELD
        );

        BigDecimal rate = toBigDecimal(record.get(RATE_FIELD));
        assertThat(rate).isEqualByComparingTo(new BigDecimal("7.800000"));

        BigDecimal baseAmount = toBigDecimal(record.get(AMOUNT_FIELD + "_base"));
        // 500 * 7.80 = 3900.00
        assertThat(baseAmount).isEqualByComparingTo(new BigDecimal("3900.00"));

        assertThat(record.get(BASE_CURRENCY_FIELD)).isEqualTo("cny");
    }

    /**
     * When crm_opp_currency_code is CNY (same as base currency),
     * the handler must use rate = 1 and set base amount = original amount.
     * No rate lookup is performed.
     */
    @Test
    void convertRecord_sameCurrencyAsCny_rateIsOneAndBaseAmountEqualsOriginal() {
        Map<String, Object> record = new HashMap<>();
        record.put(CURRENCY_FIELD, "cny");
        record.put(AMOUNT_FIELD, new BigDecimal("250000.00"));

        currencyConversionService.convertRecord(
                record,
                List.of(AMOUNT_FIELD),
                CURRENCY_FIELD,
                RATE_FIELD,
                RATE_ID_FIELD,
                BASE_CURRENCY_FIELD
        );

        BigDecimal rate = toBigDecimal(record.get(RATE_FIELD));
        assertThat(rate).isEqualByComparingTo(BigDecimal.ONE);

        BigDecimal baseAmount = toBigDecimal(record.get(AMOUNT_FIELD + "_base"));
        assertThat(baseAmount).isEqualByComparingTo(new BigDecimal("250000.00"));

        assertThat(record.get(BASE_CURRENCY_FIELD)).isEqualTo("cny");
    }

    /**
     * When crm_opp_currency_code is null (currency not specified),
     * convertRecord() treats it as same-currency and sets rate = 1.
     */
    @Test
    void convertRecord_nullCurrencyCode_treatsAsSameCurrencyAndSetsRateOne() {
        Map<String, Object> record = new HashMap<>();
        record.put(CURRENCY_FIELD, null);
        record.put(AMOUNT_FIELD, new BigDecimal("100.00"));

        currencyConversionService.convertRecord(
                record,
                List.of(AMOUNT_FIELD),
                CURRENCY_FIELD,
                RATE_FIELD,
                RATE_ID_FIELD,
                BASE_CURRENCY_FIELD
        );

        BigDecimal rate = toBigDecimal(record.get(RATE_FIELD));
        assertThat(rate).isEqualByComparingTo(BigDecimal.ONE);

        BigDecimal baseAmount = toBigDecimal(record.get(AMOUNT_FIELD + "_base"));
        assertThat(baseAmount).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    /**
     * When crm_opp_expected_amount is null, the base field must not be populated
     * (no NullPointerException — handler silently skips null amounts).
     */
    @Test
    void convertRecord_nullAmount_doesNotPopulateBaseField() {
        Map<String, Object> record = new HashMap<>();
        record.put(CURRENCY_FIELD, "usd");
        record.put(AMOUNT_FIELD, null);

        currencyConversionService.convertRecord(
                record,
                List.of(AMOUNT_FIELD),
                CURRENCY_FIELD,
                RATE_FIELD,
                RATE_ID_FIELD,
                BASE_CURRENCY_FIELD
        );

        // Rate is still populated (rate lookup occurs before amount check)
        assertThat(record.get(RATE_FIELD)).isNotNull();

        // Base amount key must not exist (or be null) — handler skips null amounts
        BigDecimal baseAmount = toBigDecimal(record.get(AMOUNT_FIELD + "_base"));
        assertThat(baseAmount).isNull();
    }

    /**
     * When the currency code has no exchange rate in either table,
     * getRate() (called internally by convertRecord) throws a BusinessException.
     * The handler propagates this to fail the command pipeline, preventing
     * a record with an incorrect base amount from being saved.
     */
    @Test
    void convertRecord_unknownCurrencyCode_throwsException() {
        Map<String, Object> record = new HashMap<>();
        record.put(CURRENCY_FIELD, "zzz"); // not in any exchange rate table
        record.put(AMOUNT_FIELD, new BigDecimal("999.00"));

        assertThatThrownBy(() ->
                currencyConversionService.convertRecord(
                        record,
                        List.of(AMOUNT_FIELD),
                        CURRENCY_FIELD,
                        RATE_FIELD,
                        RATE_ID_FIELD,
                        BASE_CURRENCY_FIELD
                )
        ).isInstanceOf(RuntimeException.class)
         .hasMessageContaining("exchange_rate_not_found");
    }

    // ===========================================================================
    // Helpers
    // ===========================================================================

    /**
     * Insert a platform-level exchange rate into ab_exchange_rate.
     * Uses a unique PID per nanoTime to avoid collisions across @BeforeEach calls.
     */
    private void insertPlatformRate(String base, String target, BigDecimal rate) {
        ExchangeRate entity = new ExchangeRate();
        entity.setPid(com.auraboot.framework.common.util.UniqueIdGenerator.generate());
        entity.setTenantId(testTenant.getId());
        entity.setBaseCurrency(base);
        entity.setTargetCurrency(target);
        entity.setRate(rate);
        entity.setEffectiveDate(TODAY);
        entity.setSource("test");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setDeletedFlag(false);
        entity.setCreatedBy(testUser.getId());
        exchangeRateMapper.insert(entity);
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
}
