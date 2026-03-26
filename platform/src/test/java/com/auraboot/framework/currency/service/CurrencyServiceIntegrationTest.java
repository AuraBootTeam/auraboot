package com.auraboot.framework.currency.service;

import com.auraboot.framework.currency.dao.entity.ExchangeRate;
import com.auraboot.framework.currency.dao.mapper.ExchangeRateMapper;
import com.auraboot.framework.currency.dto.ConversionResult;
import com.auraboot.framework.currency.dto.ExchangeRateRequest;
import com.auraboot.framework.currency.dto.ExchangeRateResponse;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for CurrencyService covering:
 * - Direct conversion
 * - Reverse conversion (inverted rate)
 * - Triangulated conversion via USD pivot
 * - CRUD operations on exchange rates
 * - Edge cases (same currency, missing rates)
 */
class CurrencyServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CurrencyService currencyService;

    @Autowired
    private ExchangeRateMapper exchangeRateMapper;

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 18);

    @BeforeEach
    void setUp() {
        // Insert test rates: USD -> CNY, USD -> EUR, USD -> JPY
        insertRate("usd", "cny", new BigDecimal("7.24500000"), TODAY);
        insertRate("usd", "eur", new BigDecimal("0.92100000"), TODAY);
        insertRate("usd", "jpy", new BigDecimal("149.50000000"), TODAY);
        insertRate("eur", "gbp", new BigDecimal("0.85700000"), TODAY);

        // Insert an older rate for historical queries
        insertRate("usd", "cny", new BigDecimal("7.10000000"), TODAY.minusDays(30));
    }

    // ==================== Direct Conversion ====================

    @Test
    void testDirectConversion() {
        ConversionResult result = currencyService.convert(
                new BigDecimal("100"), "usd", "cny", TODAY, testTenant.getId());

        assertThat(result).isNotNull();
        assertThat(result.getFromCurrency()).isEqualTo("usd");
        assertThat(result.getToCurrency()).isEqualTo("cny");
        assertThat(result.getConvertedAmount()).isEqualByComparingTo(new BigDecimal("724.50000000"));
        assertThat(result.getRateUsed()).isEqualByComparingTo(new BigDecimal("7.24500000"));
        assertThat(result.isTriangulated()).isFalse();
    }

    @Test
    void testReverseConversion() {
        // CNY -> USD should use inverted rate
        ConversionResult result = currencyService.convert(
                new BigDecimal("724.50"), "cny", "usd", TODAY, testTenant.getId());

        assertThat(result).isNotNull();
        assertThat(result.getFromCurrency()).isEqualTo("cny");
        assertThat(result.getToCurrency()).isEqualTo("usd");
        // 724.50 / 7.245 = ~100
        assertThat(result.getConvertedAmount()).isBetween(new BigDecimal("99"), new BigDecimal("101"));
        assertThat(result.isTriangulated()).isFalse();
    }

    @Test
    void testSameCurrencyConversion() {
        ConversionResult result = currencyService.convert(
                new BigDecimal("42.50"), "usd", "usd", TODAY, testTenant.getId());

        assertThat(result).isNotNull();
        assertThat(result.getConvertedAmount()).isEqualByComparingTo(new BigDecimal("42.50"));
        assertThat(result.getRateUsed()).isEqualByComparingTo(BigDecimal.ONE);
    }

    // ==================== Triangulated Conversion ====================

    @Test
    void testTriangulatedConversion() {
        // CNY -> EUR: no direct rate, should go CNY -> USD -> EUR
        ConversionResult result = currencyService.convert(
                new BigDecimal("724.50"), "cny", "eur", TODAY, testTenant.getId());

        assertThat(result).isNotNull();
        assertThat(result.isTriangulated()).isTrue();
        // 724.50 / 7.245 * 0.921 = ~92.1
        assertThat(result.getConvertedAmount()).isBetween(new BigDecimal("90"), new BigDecimal("95"));
    }

    @Test
    void testTriangulatedConversionJpyToEur() {
        // JPY -> EUR: no direct rate
        ConversionResult result = currencyService.convert(
                new BigDecimal("14950"), "jpy", "eur", TODAY, testTenant.getId());

        assertThat(result).isNotNull();
        assertThat(result.isTriangulated()).isTrue();
        // 14950 / 149.5 * 0.921 = ~92.1
        assertThat(result.getConvertedAmount()).isBetween(new BigDecimal("90"), new BigDecimal("95"));
    }

    // ==================== Historical Rate ====================

    @Test
    void testHistoricalConversion() {
        LocalDate oldDate = TODAY.minusDays(30);
        ConversionResult result = currencyService.convert(
                new BigDecimal("100"), "usd", "cny", oldDate, testTenant.getId());

        assertThat(result).isNotNull();
        assertThat(result.getConvertedAmount()).isEqualByComparingTo(new BigDecimal("710.00000000"));
        assertThat(result.getRateUsed()).isEqualByComparingTo(new BigDecimal("7.10000000"));
    }

    // ==================== CRUD Operations ====================

    @Test
    void testSaveAndGetRate() {
        ExchangeRateRequest req = new ExchangeRateRequest();
        req.setBaseCurrency("gbp");
        req.setTargetCurrency("chf");
        req.setRate(new BigDecimal("1.13500000"));
        req.setEffectiveDate(TODAY);
        req.setSource("manual");

        ExchangeRateResponse saved = currencyService.saveRate(req, testTenant.getId(), testUser.getId());

        assertThat(saved).isNotNull();
        assertThat(saved.getPid()).isNotNull();
        assertThat(saved.getBaseCurrency()).isEqualTo("gbp");
        assertThat(saved.getTargetCurrency()).isEqualTo("chf");
        assertThat(saved.getRate()).isEqualByComparingTo(new BigDecimal("1.13500000"));

        // Retrieve it
        ExchangeRateResponse fetched = currencyService.getLatestRate("gbp", "chf", testTenant.getId());
        assertThat(fetched.getPid()).isEqualTo(saved.getPid());
    }

    @Test
    void testUpdateExistingRate() {
        // Save initial rate
        ExchangeRateRequest req = new ExchangeRateRequest();
        req.setBaseCurrency("aud");
        req.setTargetCurrency("nzd");
        req.setRate(new BigDecimal("1.08000000"));
        req.setEffectiveDate(TODAY);
        currencyService.saveRate(req, testTenant.getId(), testUser.getId());

        // Update same pair + date
        req.setRate(new BigDecimal("1.09500000"));
        ExchangeRateResponse updated = currencyService.saveRate(req, testTenant.getId(), testUser.getId());

        assertThat(updated.getRate()).isEqualByComparingTo(new BigDecimal("1.09500000"));

        // Verify only one rate exists for this pair + date
        List<ExchangeRateResponse> rates = currencyService.listRates(testTenant.getId(), "aud", TODAY);
        long count = rates.stream()
                .filter(r -> "nzd".equals(r.getTargetCurrency()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void testDeleteRate() {
        ExchangeRateRequest req = new ExchangeRateRequest();
        req.setBaseCurrency("sgd");
        req.setTargetCurrency("myr");
        req.setRate(new BigDecimal("3.45000000"));
        req.setEffectiveDate(TODAY);
        ExchangeRateResponse saved = currencyService.saveRate(req, testTenant.getId(), testUser.getId());

        // Verify it exists before deletion
        ExchangeRateResponse before = currencyService.getLatestRate("sgd", "myr", testTenant.getId());
        assertThat(before).isNotNull();

        currencyService.deleteRate(saved.getPid(), testTenant.getId());

        // After soft-delete, findLatestRate should return null (filters deleted_flag)
        ExchangeRate byLatest = exchangeRateMapper.findLatestRate(
                testTenant.getId(), "sgd", "myr", TODAY);
        assertThat(byLatest).isNull();

        // deleteRate on non-existent PID should throw
        assertThatThrownBy(() -> currencyService.deleteRate("nonexistent_pid", testTenant.getId()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void testSameCurrencyRateSaveFails() {
        ExchangeRateRequest req = new ExchangeRateRequest();
        req.setBaseCurrency("usd");
        req.setTargetCurrency("usd");
        req.setRate(BigDecimal.ONE);
        req.setEffectiveDate(TODAY);

        assertThatThrownBy(() -> currencyService.saveRate(req, testTenant.getId(), testUser.getId()))
                .isInstanceOf(RuntimeException.class);
    }

    // ==================== List Operations ====================

    @Test
    void testListLatestRates() {
        List<ExchangeRateResponse> rates = currencyService.listLatestRates(testTenant.getId());
        assertThat(rates).isNotNull();
        // We inserted 4 distinct pairs + 1 older duplicate = 4 latest
        assertThat(rates).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void testListRatesByBaseCurrency() {
        List<ExchangeRateResponse> rates = currencyService.listRates(testTenant.getId(), "usd", null);
        assertThat(rates).isNotNull();
        assertThat(rates).hasSizeGreaterThanOrEqualTo(3); // USD->CNY, USD->EUR, USD->JPY
    }

    @Test
    void testGetSupportedCurrencies() {
        List<String> currencies = currencyService.getSupportedCurrencies();
        assertThat(currencies).isNotNull();
        assertThat(currencies).contains("usd", "cny", "eur", "jpy");
        assertThat(currencies).hasSizeGreaterThanOrEqualTo(20);
    }

    // ==================== Error Cases ====================

    @Test
    void testMissingRateThrowsException() {
        assertThatThrownBy(() -> currencyService.getLatestRate("xyz", "abc", testTenant.getId()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void testConversionWithNoAvailableRateThrows() {
        // No rate exists for BRL -> ZAR, and neither has a USD rate in test data
        assertThatThrownBy(() ->
                currencyService.convert(new BigDecimal("100"), "brl", "zar", TODAY, testTenant.getId()))
                .isInstanceOf(RuntimeException.class);
    }

    // ==================== Helper ====================

    private void insertRate(String base, String target, BigDecimal rate, LocalDate date) {
        ExchangeRate entity = new ExchangeRate();
        entity.setPid("test" + System.nanoTime());
        entity.setTenantId(testTenant.getId());
        entity.setBaseCurrency(base);
        entity.setTargetCurrency(target);
        entity.setRate(rate);
        entity.setEffectiveDate(date);
        entity.setSource("manual");
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        entity.setDeletedFlag(false);
        entity.setCreatedBy(testUser.getId());
        exchangeRateMapper.insert(entity);
    }
}
