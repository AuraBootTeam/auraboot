package com.auraboot.framework.currency.service.impl;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.currency.dao.entity.ExchangeRate;
import com.auraboot.framework.currency.dto.ConversionResult;
import com.auraboot.framework.currency.dto.ExchangeRateRequest;
import com.auraboot.framework.currency.dto.ExchangeRateResponse;
import com.auraboot.framework.currency.service.CurrencyService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-stack coverage IT for {@link CurrencyServiceImpl} — exchange-rate CRUD,
 * direct/reverse/triangulated conversion, soft delete, and every listRates branch.
 * No mocks: a dedicated synthetic tenant on the real DB ({@code ab_exchange_rate})
 * exercises the custom {@code @Select} SQL (findLatestRate / findAllLatestRates /
 * DISTINCT ON) against PostgreSQL. Cleaned up by tenant via raw SQL.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CurrencyServiceImpl Coverage IT — rates CRUD + conversion paths")
class CurrencyServiceImplCoverageIT {

    private static final long TENANT_ID = 991_300_001L;
    private static final long USER_ID = 991_300_002L;
    private static final LocalDate DAY = LocalDate.of(2026, 6, 15);

    @Autowired
    private CurrencyService currencyService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void seedTenant() {
        // ab_exchange_rate.tenant_id carries an FK to ab_tenant; provision a dedicated
        // synthetic tenant for this IT and remove it again on teardown.
        jdbcTemplate.update(
                "INSERT INTO ab_tenant (id, pid, name, status) VALUES (?, ?, ?, 'active') ON CONFLICT (id) DO NOTHING",
                TENANT_ID, "ccy-tenant-pid", "currency-it-tenant");
    }

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, USER_ID, "ccy-test-pid", "ccy-test-user");
    }

    @AfterAll
    void cleanup() {
        try {
            jdbcTemplate.update("DELETE FROM ab_exchange_rate WHERE tenant_id = ?", TENANT_ID);
            jdbcTemplate.update("DELETE FROM ab_tenant WHERE id = ?", TENANT_ID);
        } finally {
            MetaContext.clear();
        }
    }

    private ExchangeRateRequest request(String base, String target, String rate, LocalDate date) {
        ExchangeRateRequest req = new ExchangeRateRequest();
        req.setBaseCurrency(base);
        req.setTargetCurrency(target);
        req.setRate(new BigDecimal(rate));
        req.setEffectiveDate(date);
        req.setSource("integration-test");
        return req;
    }

    @Test
    @DisplayName("saveRate creates then updates the same pair+date; same-currency is rejected")
    void saveRateCreateUpdateAndReject() {
        ExchangeRateResponse created = currencyService.saveRate(request("USD", "EUR", "0.90", DAY), TENANT_ID, USER_ID);
        assertNotNull(created.getPid());
        assertEquals("usd", created.getBaseCurrency());
        assertEquals("eur", created.getTargetCurrency());
        assertEquals(new BigDecimal("0.90"), created.getRate());
        assertEquals(DAY, created.getEffectiveDate());
        assertEquals("integration-test", created.getSource());
        assertNotNull(created.getCreatedAt());
        assertNotNull(created.getUpdatedAt());

        // Second save for the same pair + date must update in place, not duplicate.
        ExchangeRateResponse updated = currencyService.saveRate(request("usd", "eur", "0.95", DAY), TENANT_ID, USER_ID);
        assertEquals(created.getPid(), updated.getPid());
        assertEquals(new BigDecimal("0.95"), updated.getRate());

        List<ExchangeRateResponse> all = currencyService.listRates(TENANT_ID, "usd", null);
        assertEquals(1, all.stream().filter(r -> r.getTargetCurrency().equals("eur")).count());

        RuntimeException same = assertThrows(RuntimeException.class,
                () -> currencyService.saveRate(request("USD", "USD", "1", DAY), TENANT_ID, USER_ID));
        assertTrue(same.getMessage().contains("must be different"));
    }

    @Test
    @DisplayName("convert uses the direct rate when one exists")
    void convertDirect() {
        currencyService.saveRate(request("GBP", "CNY", "9.2", DAY), TENANT_ID, USER_ID);

        ConversionResult result = currencyService.convert(new BigDecimal("10"), "GBP", "cny", DAY, TENANT_ID);
        assertEquals(new BigDecimal("92.00000000"), result.getConvertedAmount());
        // rate is numeric(18,8) — compare numerically, not by scale
        assertEquals(0, result.getRateUsed().compareTo(new BigDecimal("9.2")));
        assertFalse(result.isTriangulated());
        assertEquals(DAY, result.getRateDate());
    }

    @Test
    @DisplayName("convert inverts the stored reverse rate when only the opposite pair exists")
    void convertReverse() {
        // Only JPY->HKD stored; HKD->JPY must invert it.
        currencyService.saveRate(request("jpy", "hkd", "0.05", DAY), TENANT_ID, USER_ID);

        ConversionResult result = currencyService.convert(new BigDecimal("100"), "hkd", "JPY", DAY, TENANT_ID);
        assertEquals(0, result.getConvertedAmount().compareTo(new BigDecimal("2000")));
        assertEquals(0, result.getRateUsed().compareTo(new BigDecimal("20")));
        assertFalse(result.isTriangulated());
    }

    @Test
    @DisplayName("convert triangulates via the USD pivot when no direct or reverse pair exists")
    void convertTriangulated() {
        currencyService.saveRate(request("usd", "sgd", "1.35", DAY), TENANT_ID, USER_ID);
        currencyService.saveRate(request("usd", "inr", "83.00", DAY), TENANT_ID, USER_ID);

        // SGD -> INR has no direct/reverse rate; both legs resolve through USD.
        ConversionResult result = currencyService.convert(new BigDecimal("2"), "sgd", "inr", DAY, TENANT_ID);
        assertEquals(0, result.getConvertedAmount().compareTo(new BigDecimal("122.96296284"))); // 2 * (1/1.35) * 83
        assertTrue(result.isTriangulated());
    }

    @Test
    @DisplayName("convert with same currency short-circuits with rate 1")
    void convertSameCurrency() {
        ConversionResult result = currencyService.convert(new BigDecimal("5"), "usd", "USD", null, TENANT_ID);
        assertEquals(new BigDecimal("5"), result.getConvertedAmount());
        assertEquals(BigDecimal.ONE, result.getRateUsed());
        assertFalse(result.isTriangulated());
    }

    @Test
    @DisplayName("convert without any resolvable rate path fails with a clear error")
    void convertMissingPath() {
        RuntimeException firstLeg = assertThrows(RuntimeException.class,
                () -> currencyService.convert(BigDecimal.ONE, "gbp", "jpy", DAY, TENANT_ID));
        assertTrue(firstLeg.getMessage().contains("No exchange rate found"));

        // Second leg missing: base -> USD exists, but USD -> target does not.
        currencyService.saveRate(request("chf", "usd", "1.1", DAY), TENANT_ID, USER_ID);
        RuntimeException secondLeg = assertThrows(RuntimeException.class,
                () -> currencyService.convert(BigDecimal.ONE, "chf", "krw", DAY, TENANT_ID));
        assertTrue(secondLeg.getMessage().contains("No exchange rate found"));
    }

    @Test
    @DisplayName("getLatestRate returns the most recent rate on or before today, or throws")
    void getLatestRate() {
        currencyService.saveRate(request("aud", "cad", "0.88", DAY.minusDays(30)), TENANT_ID, USER_ID);
        currencyService.saveRate(request("aud", "cad", "0.91", DAY.minusDays(1)), TENANT_ID, USER_ID);

        ExchangeRateResponse latest = currencyService.getLatestRate("AUD", "cad", TENANT_ID);
        assertEquals(0, latest.getRate().compareTo(new BigDecimal("0.91")));

        RuntimeException missing = assertThrows(RuntimeException.class,
                () -> currencyService.getLatestRate("cad", "nzd", TENANT_ID));
        assertTrue(missing.getMessage().contains("No exchange rate found"));
    }

    @Test
    @DisplayName("deleteRate soft-deletes by pid and rejects unknown pids")
    void deleteRate() {
        ExchangeRateResponse created = currencyService.saveRate(request("nzd", "thb", "18.5", DAY), TENANT_ID, USER_ID);
        currencyService.deleteRate(created.getPid(), TENANT_ID);

        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted_flag::int FROM ab_exchange_rate WHERE pid = ?", Integer.class, created.getPid());
        assertEquals(1, deleted);
        assertTrue(currencyService.listRates(TENANT_ID, "nzd", null).isEmpty());

        RuntimeException missing = assertThrows(RuntimeException.class,
                () -> currencyService.deleteRate("no-such-pid", TENANT_ID));
        assertTrue(missing.getMessage().contains("not found"));
    }

    @Test
    @DisplayName("listRates covers all four filter branches and listLatestRates dedupes to the newest")
    void listRatesBranches() {
        // Other tests in this class share the synthetic tenant — start from a clean slate
        // so the latest-per-pair assertion below only sees this test's own fixture.
        jdbcTemplate.update("DELETE FROM ab_exchange_rate WHERE tenant_id = ?", TENANT_ID);
        currencyService.saveRate(request("sek", "dkk", "0.65", DAY.minusDays(10)), TENANT_ID, USER_ID);
        currencyService.saveRate(request("sek", "nok", "0.95", DAY.minusDays(5)), TENANT_ID, USER_ID);
        currencyService.saveRate(request("sek", "dkk", "0.66", DAY.minusDays(2)), TENANT_ID, USER_ID);

        // base + date branch: exact-date filter, ordered by target currency
        List<ExchangeRateResponse> byBaseAndDate = currencyService.listRates(TENANT_ID, "sek", DAY.minusDays(10));
        assertEquals(1, byBaseAndDate.size());
        assertEquals("dkk", byBaseAndDate.get(0).getTargetCurrency());

        // base only
        List<ExchangeRateResponse> byBase = currencyService.listRates(TENANT_ID, "sek", null);
        assertEquals(3, byBase.size()); // findByBaseCurrency returns every stored row, not deduped

        // date only
        List<ExchangeRateResponse> byDate = currencyService.listRates(TENANT_ID, null, DAY.minusDays(5));
        assertEquals(1, byDate.size());
        assertEquals("nok", byDate.get(0).getTargetCurrency());

        // neither: latest per pair
        List<ExchangeRateResponse> latest = currencyService.listLatestRates(TENANT_ID);
        assertEquals(2, latest.size()); // DISTINCT ON dedupes sek->dkk to its newest row
        ExchangeRateResponse sekDkk = latest.stream()
                .filter(r -> r.getTargetCurrency().equals("dkk")).findFirst().orElseThrow();
        assertEquals(0, sekDkk.getRate().compareTo(new BigDecimal("0.66")));
    }

    @Test
    @DisplayName("getSupportedCurrencies exposes the ISO 4217 list including the USD pivot")
    void supportedCurrencies() {
        List<String> currencies = currencyService.getSupportedCurrencies();
        assertTrue(currencies.contains("usd"));
        assertTrue(currencies.size() >= 30);
    }
}
