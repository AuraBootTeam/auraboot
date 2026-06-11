package com.auraboot.framework.currency.handler;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-stack integration test for {@link DefaultCurrencyConversionHandler}.
 *
 * <p>Part of OSS coverage initiative (tracker:
 * {@code docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md}).
 * {@code DefaultCurrencyConversionHandler} was a near-zero (~0.7%) class; this exercises
 * the real handler against the real shared database (no mocks, per AGENTS.md §2.2 seam
 * discipline), covering:
 * <ul>
 *   <li>getHandlerName()</li>
 *   <li>execute() in header mode with/without currency fields, amount fields, same currency</li>
 *   <li>execute() in line mode with valid/zero/negative/null parentRateField</li>
 *   <li>resolveRate() — same currency identity; SPI absent + table present → seeded row path;
 *       SPI absent + table absent → identity fallback; inverse rate path</li>
 *   <li>resolveBaseCurrency() — SPI absent + table present with base currency row; default CNY fallback</li>
 *   <li>parseConfig() — null/blank/valid JSON/invalid JSON</li>
 *   <li>toBigDecimal() with BigDecimal, Number, String, and invalid inputs</li>
 *   <li>stringList() and putIfConfigured() edge cases</li>
 * </ul>
 *
 * <p>Uses the {@code integration-test} profile (shared Postgres on :5432). All data is
 * scoped to a dedicated test tenant and hard-deleted in tearDown.  The temporary
 * {@code mt_fin_exchange_rate} and {@code mt_fin_currency} tables are created (and dropped)
 * around each test to exercise the DSL table fallback path, which requires those tables to
 * exist in production but not in the base OSS schema.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("DefaultCurrencyConversionHandler Real-Stack Integration Test")
class DefaultCurrencyConversionHandlerIntegrationTest {

    private static final String CODE_PREFIX = "covcurr";
    private static final String RUN = Long.toString(Math.abs(System.nanoTime()), 36);

    @Autowired
    @Qualifier("defaultCurrencyConversionHandler")
    private DefaultCurrencyConversionHandler handler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserService userService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantMemberService tenantMemberService;

    private User testUser;
    private Tenant testTenant;
    private boolean finTablesCreated = false;

    @BeforeEach
    void setUp() {
        String email = "covcurr-test@auraboot.com";
        testUser = userService.findByEmail(email);
        if (testUser == null) {
            testUser = userService.signUp(email, "test-password-123");
        }

        String tenantName = "covcurr-test-tenant";
        testTenant = tenantService.findByName(tenantName);
        if (testTenant == null) {
            Tenant t = new Tenant();
            t.setPid(UniqueIdGenerator.generate());
            t.setName(tenantName);
            t.setDisplayName("Currency Conversion Coverage Test Tenant");
            t.setStatus("active");
            t.setContactEmail("admin@covcurr-test.com");
            t.setDescription("Test tenant for DefaultCurrencyConversionHandler IT");
            t.setDeletedFlag(false);
            t.setCreatedAt(Instant.now());
            t.setUpdatedAt(Instant.now());
            testTenant = tenantService.createTenant(t);
        }

        TenantMember member = tenantMemberService.findByTenantIdAndUserId(
                testTenant.getId(), testUser.getId());
        if (member == null) {
            tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
        }

        MetaContext.setContext(testTenant.getId(), testUser.getId(),
                testUser.getPid(), testUser.getUserName());

        createFinTablesIfAbsent();
        wipeFinData();
    }

    @AfterEach
    void tearDown() {
        try {
            wipeFinData();
        } catch (Exception e) {
            log.warn("currency cleanup failed: {}", e.getMessage());
        } finally {
            MetaContext.clear();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Table lifecycle helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Creates the DSL finance tables needed for the DSL-table fallback path. */
    private void createFinTablesIfAbsent() {
        Boolean rateExists = jdbcTemplate.queryForObject(
                "SELECT to_regclass('mt_fin_exchange_rate') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(rateExists)) {
            jdbcTemplate.execute("""
                    CREATE TABLE mt_fin_exchange_rate (
                        id              BIGSERIAL PRIMARY KEY,
                        tenant_id       BIGINT NOT NULL,
                        fin_exr_from_currency VARCHAR(16) NOT NULL,
                        fin_exr_to_currency   VARCHAR(16) NOT NULL,
                        fin_exr_rate          NUMERIC(20,8) NOT NULL,
                        fin_exr_effective_date DATE NOT NULL,
                        fin_exr_rate_type     VARCHAR(16) NOT NULL DEFAULT 'spot',
                        updated_at      TIMESTAMP WITH TIME ZONE DEFAULT now()
                    )
                    """);
        }

        Boolean curExists = jdbcTemplate.queryForObject(
                "SELECT to_regclass('mt_fin_currency') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(curExists)) {
            jdbcTemplate.execute("""
                    CREATE TABLE mt_fin_currency (
                        id              BIGSERIAL PRIMARY KEY,
                        tenant_id       BIGINT NOT NULL,
                        fin_cur_code    VARCHAR(16) NOT NULL,
                        fin_cur_is_base BOOLEAN NOT NULL DEFAULT false,
                        updated_at      TIMESTAMP WITH TIME ZONE DEFAULT now()
                    )
                    """);
        }
        finTablesCreated = true;
    }

    private void wipeFinData() {
        if (finTablesCreated) {
            Long tid = testTenant.getId();
            try {
                jdbcTemplate.update("DELETE FROM mt_fin_exchange_rate WHERE tenant_id = ?", tid);
                jdbcTemplate.update("DELETE FROM mt_fin_currency WHERE tenant_id = ?", tid);
            } catch (Exception e) {
                log.debug("fin wipe skipped (table may not exist): {}", e.getMessage());
            }
        }
    }

    /** Seed a spot rate row for the test tenant. Returns the generated id. */
    private long seedRate(String fromCurrency, String toCurrency, BigDecimal rate, LocalDate effectiveDate) {
        jdbcTemplate.update("""
                INSERT INTO mt_fin_exchange_rate
                    (tenant_id, fin_exr_from_currency, fin_exr_to_currency, fin_exr_rate,
                     fin_exr_effective_date, fin_exr_rate_type, updated_at)
                VALUES (?, ?, ?, ?, ?, 'spot', now())
                """,
                testTenant.getId(),
                fromCurrency.toLowerCase(),
                toCurrency.toLowerCase(),
                rate,
                effectiveDate);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM mt_fin_exchange_rate WHERE tenant_id=? AND fin_exr_from_currency=? AND fin_exr_to_currency=? ORDER BY id DESC LIMIT 1",
                Long.class,
                testTenant.getId(), fromCurrency.toLowerCase(), toCurrency.toLowerCase());
    }

    /** Seed a base-currency row for the test tenant. */
    private void seedBaseCurrency(String code) {
        jdbcTemplate.update("""
                INSERT INTO mt_fin_currency (tenant_id, fin_cur_code, fin_cur_is_base, updated_at)
                VALUES (?, ?, true, now())
                """,
                testTenant.getId(), code.toLowerCase());
    }

    /** Build a minimal header-mode context. */
    private CommandHandlerContext ctx(String ruleConfig, Map<String, Object> payload) {
        return CommandHandlerContext.builder()
                .commandCode("test_currency_convert")
                .modelCode("test_sales_order")
                .tenantId(testTenant.getId())
                .userId(testUser.getId())
                .payload(payload)
                .ruleConfig(ruleConfig)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getHandlerName()
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getHandlerName returns 'currencyConversionHandler'")
    void getHandlerName() {
        assertEquals("currencyConversionHandler", handler.getHandlerName());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // parseConfig branches
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("execute with null ruleConfig does not throw (empty config path)")
    void executeNullConfig() {
        Map<String, Object> result = handler.execute(ctx(null, Map.of()));
        assertNotNull(result);
    }

    @Test
    @DisplayName("execute with blank ruleConfig does not throw")
    void executeBlankConfig() {
        Map<String, Object> result = handler.execute(ctx("   ", Map.of()));
        assertNotNull(result);
    }

    @Test
    @DisplayName("execute with invalid (non-JSON) ruleConfig falls back to empty config, no throw")
    void executeInvalidConfig() {
        Map<String, Object> result = handler.execute(ctx("not-json", Map.of()));
        assertNotNull(result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Header mode — base currency resolution + amountFields
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("header mode: resolves base currency from DSL table when seeded")
    void headerModeReadsBaseCurrencyFromDslTable() {
        seedBaseCurrency("USD");

        String config = """
                {
                  "baseCurrencyField": "base_currency",
                  "currencyField": "currency",
                  "rateField": "exchange_rate"
                }
                """;
        Map<String, Object> payload = Map.of("currency", "USD");
        Map<String, Object> result = handler.execute(ctx(config, payload));

        assertEquals("usd", result.get("base_currency"));
    }

    @Test
    @DisplayName("header mode: falls back to CNY when no base-currency row and no SPI")
    void headerModeFallsBackToCny() {
        // No base-currency row seeded — DSL table exists but is empty for this tenant
        String config = """
                {
                  "baseCurrencyField": "base_currency",
                  "rateField": "rate"
                }
                """;
        Map<String, Object> result = handler.execute(ctx(config, Map.of()));
        assertEquals("CNY", result.get("base_currency"));
    }

    @Test
    @DisplayName("header mode: same-currency conversion yields rate=1 and correct base amounts")
    void headerModeSameCurrencyYieldsRateOne() {
        seedBaseCurrency("USD");

        String config = """
                {
                  "baseCurrencyField": "base_currency",
                  "currencyField": "currency",
                  "rateField": "rate",
                  "amountFields": ["price", "total"]
                }
                """;
        // docCurrency == baseCurrency → resolveRate returns identity (rate=1)
        Map<String, Object> payload = Map.of(
                "currency", "USD",
                "price", new BigDecimal("100.00"),
                "total", new BigDecimal("250.00")
        );

        Map<String, Object> result = handler.execute(ctx(config, payload));

        assertEquals(BigDecimal.ONE, result.get("rate"));
        // price_base = 100.00 * 1 = 100.00
        assertEquals(new BigDecimal("100.00"), result.get("price_base"));
        assertEquals(new BigDecimal("250.00"), result.get("total_base"));
    }

    @Test
    @DisplayName("header mode: uses seeded DSL exchange rate and writes rate + rateId + base amounts")
    void headerModeDslRateLookup() {
        seedBaseCurrency("CNY");
        long rateId = seedRate("usd", "cny", new BigDecimal("7.24"), LocalDate.now());

        String config = """
                {
                  "baseCurrencyField": "base_currency",
                  "currencyField": "doc_currency",
                  "rateField": "exr_rate",
                  "rateIdField": "exr_rate_id",
                  "amountFields": ["amount"]
                }
                """;
        Map<String, Object> payload = Map.of(
                "doc_currency", "USD",
                "amount", new BigDecimal("100.00")
        );

        Map<String, Object> result = handler.execute(ctx(config, payload));

        assertEquals("cny", result.get("base_currency"));
        BigDecimal rate = (BigDecimal) result.get("exr_rate");
        assertNotNull(rate);
        assertEquals(0, new BigDecimal("7.24").compareTo(rate));
        assertEquals(rateId, ((Number) result.get("exr_rate_id")).longValue());
        // 100 * 7.24 = 724.00
        assertEquals(new BigDecimal("724.00"), result.get("amount_base"));
    }

    @Test
    @DisplayName("header mode: uses inverse DSL rate when direct rate absent")
    void headerModeInverseRateFallback() {
        // base currency = CNY; doc currency = USD
        // Seed cny→usd = 0.14 (i.e. the INVERSE direction: toCurrency→fromCurrency).
        // resolveRate("usd", "cny", ...) calls resolveRateFromDslTable("usd","cny") → no row (only cny→usd).
        // Then calls resolveRateFromDslTable("cny","usd") → finds 0.14 → inverse = 1/0.14 ≈ 7.14.
        seedBaseCurrency("CNY");
        seedRate("cny", "usd", new BigDecimal("0.14"), LocalDate.now());

        String config = """
                {
                  "baseCurrencyField": "base_currency",
                  "currencyField": "doc_currency",
                  "rateField": "exr_rate",
                  "amountFields": ["amount"]
                }
                """;
        // docCurrency = "USD", baseCurrency = "CNY"
        // → resolveRate("usd", "cny") — direct row absent, inverse cny→usd=0.14 found
        Map<String, Object> payload = Map.of(
                "doc_currency", "USD",
                "amount", new BigDecimal("100.00")
        );

        Map<String, Object> result = handler.execute(ctx(config, payload));

        BigDecimal rate = (BigDecimal) result.get("exr_rate");
        assertNotNull(rate);
        // inverse of 0.14 = ~7.142857...
        assertTrue(rate.compareTo(new BigDecimal("7")) > 0 && rate.compareTo(new BigDecimal("8")) < 0,
                "Expected inverse rate ~7.14 but got: " + rate);
        BigDecimal amountBase = (BigDecimal) result.get("amount_base");
        assertNotNull(amountBase);
        assertTrue(amountBase.compareTo(new BigDecimal("700")) > 0,
                "Expected base amount > 700 but got: " + amountBase);
    }

    @Test
    @DisplayName("header mode: no DSL rate row, no SPI → identity rate, amounts unchanged")
    void headerModeNoRateRowFallsBackToIdentity() {
        seedBaseCurrency("CNY");
        // No rate rows seeded: different currencies → no direct, no inverse → identity(1)

        String config = """
                {
                  "baseCurrencyField": "base_currency",
                  "currencyField": "doc_currency",
                  "rateField": "exr_rate",
                  "amountFields": ["amount"]
                }
                """;
        Map<String, Object> payload = Map.of(
                "doc_currency", "EUR",
                "amount", new BigDecimal("500.00")
        );

        Map<String, Object> result = handler.execute(ctx(config, payload));

        BigDecimal rate = (BigDecimal) result.get("exr_rate");
        assertNotNull(rate);
        assertEquals(0, BigDecimal.ONE.compareTo(rate));
        assertEquals(new BigDecimal("500.00"), result.get("amount_base"));
    }

    @Test
    @DisplayName("header mode: null payload treated as empty map (no NPE)")
    void headerModeNullPayload() {
        String config = """
                {"baseCurrencyField": "base_currency", "rateField": "rate"}
                """;
        CommandHandlerContext ctxNullPayload = CommandHandlerContext.builder()
                .commandCode("test_cmd")
                .modelCode("test_model")
                .tenantId(testTenant.getId())
                .userId(testUser.getId())
                .payload(null)
                .ruleConfig(config)
                .build();

        Map<String, Object> result = handler.execute(ctxNullPayload);
        assertNotNull(result);
        assertEquals("CNY", result.get("base_currency"));
    }

    @Test
    @DisplayName("header mode: currencyField absent in payload → docCurrency falls back to baseCurrency")
    void headerModeMissingCurrencyFieldInPayload() {
        seedBaseCurrency("EUR");

        String config = """
                {
                  "baseCurrencyField": "base_currency",
                  "currencyField": "currency",
                  "rateField": "rate"
                }
                """;
        // no "currency" key in payload → docCurrency = null → resolveRate(null, base) → same-currency
        Map<String, Object> result = handler.execute(ctx(config, Map.of()));

        assertEquals("eur", result.get("base_currency"));
        // rate should be 1 (same-currency branch when docCurrency==null falls back to baseCurrency)
        assertEquals(BigDecimal.ONE, result.get("rate"));
    }

    @Test
    @DisplayName("header mode: amountField missing in payload → base amount not written")
    void headerModeAmountFieldMissingInPayload() {
        String config = """
                {"amountFields": ["unit_price"]}
                """;
        // unit_price not in payload → toBigDecimal(null) → no entry added
        Map<String, Object> result = handler.execute(ctx(config, Map.of()));
        assertFalse(result.containsKey("unit_price_base"));
    }

    @Test
    @DisplayName("header mode: rateField blank → putIfConfigured skips null field")
    void headerModeNullFieldNames() {
        String config = """
                {}
                """;
        Map<String, Object> result = handler.execute(ctx(config, Map.of()));
        assertNotNull(result);
        // baseCurrencyField=null → not written
        assertFalse(result.containsKey("null"));
    }

    @Test
    @DisplayName("header mode: amount as Number (int) and String are correctly parsed")
    void headerModeAmountVariousTypes() {
        String config = """
                {
                  "amountFields": ["a_int", "a_str", "a_bad"]
                }
                """;
        Map<String, Object> payload = Map.of(
                "a_int", 200,          // Number path
                "a_str", "50.5",       // String path
                "a_bad", "not-a-number" // toBigDecimal returns null → skipped
        );

        Map<String, Object> result = handler.execute(ctx(config, payload));

        assertEquals(new BigDecimal("200.00"), result.get("a_int_base"));
        assertEquals(new BigDecimal("50.50"), result.get("a_str_base"));
        assertFalse(result.containsKey("a_bad_base"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Header mode — tenantId null (no DSL table lookup attempted)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("header mode: tenantId null → DSL table path skipped, identity rate applied")
    void headerModeNullTenantId() {
        String config = """
                {
                  "baseCurrencyField": "base_currency",
                  "currencyField": "currency",
                  "rateField": "rate",
                  "amountFields": ["amount"]
                }
                """;
        CommandHandlerContext ctxNoTenant = CommandHandlerContext.builder()
                .commandCode("test_cmd")
                .modelCode("test_model")
                .tenantId(null)       // null tenantId → jdbcTemplate path returns null early
                .userId(testUser.getId())
                .payload(Map.of("currency", "USD", "amount", new BigDecimal("100")))
                .ruleConfig(config)
                .build();

        Map<String, Object> result = handler.execute(ctxNoTenant);
        assertNotNull(result);
        // baseCurrency falls back to DEFAULT_BASE_CURRENCY = "CNY"
        assertEquals("CNY", result.get("base_currency"));
        // rate = identity(1) since no DSL lookup with null tenantId
        assertEquals(BigDecimal.ONE, result.get("rate"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Line mode
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("line mode: applies parent rate to amount fields")
    void lineModeAppliesParentRate() {
        String config = """
                {
                  "mode": "line",
                  "parentRateField": "header_rate",
                  "amountFields": ["line_amount", "line_tax"]
                }
                """;
        Map<String, Object> payload = Map.of(
                "header_rate", new BigDecimal("7.5"),
                "line_amount", new BigDecimal("100.00"),
                "line_tax", new BigDecimal("10.00")
        );

        Map<String, Object> result = handler.execute(ctx(config, payload));

        assertEquals(new BigDecimal("750.00"), result.get("line_amount_base"));
        assertEquals(new BigDecimal("75.00"), result.get("line_tax_base"));
    }

    @Test
    @DisplayName("line mode: zero rate → falls back to rate=1 (identity)")
    void lineModeZeroRateFallback() {
        String config = """
                {
                  "mode": "line",
                  "parentRateField": "rate",
                  "amountFields": ["amount"]
                }
                """;
        Map<String, Object> payload = Map.of(
                "rate", new BigDecimal("0"),
                "amount", new BigDecimal("200.00")
        );

        Map<String, Object> result = handler.execute(ctx(config, payload));
        assertEquals(new BigDecimal("200.00"), result.get("amount_base"));
    }

    @Test
    @DisplayName("line mode: negative rate → falls back to rate=1")
    void lineModeNegativeRateFallback() {
        String config = """
                {
                  "mode": "line",
                  "parentRateField": "rate",
                  "amountFields": ["amount"]
                }
                """;
        Map<String, Object> payload = Map.of(
                "rate", new BigDecimal("-1"),
                "amount", new BigDecimal("100.00")
        );

        Map<String, Object> result = handler.execute(ctx(config, payload));
        assertEquals(new BigDecimal("100.00"), result.get("amount_base"));
    }

    @Test
    @DisplayName("line mode: null parentRateField → identity rate applied")
    void lineModeNullParentRateField() {
        String config = """
                {
                  "mode": "line",
                  "amountFields": ["amount"]
                }
                """;
        Map<String, Object> payload = Map.of("amount", new BigDecimal("300.00"));

        Map<String, Object> result = handler.execute(ctx(config, payload));
        assertEquals(new BigDecimal("300.00"), result.get("amount_base"));
    }

    @Test
    @DisplayName("line mode: rate as integer (Number path in toBigDecimal)")
    void lineModeIntegerRate() {
        String config = """
                {
                  "mode": "line",
                  "parentRateField": "rate",
                  "amountFields": ["amount"]
                }
                """;
        Map<String, Object> payload = Map.of(
                "rate", 3,           // Integer (Number subtype)
                "amount", new BigDecimal("50.00")
        );

        Map<String, Object> result = handler.execute(ctx(config, payload));
        assertEquals(new BigDecimal("150.00"), result.get("amount_base"));
    }

    @Test
    @DisplayName("line mode: empty amountFields list → empty result")
    void lineModeEmptyAmountFields() {
        String config = """
                {
                  "mode": "line",
                  "parentRateField": "rate",
                  "amountFields": []
                }
                """;
        Map<String, Object> result = handler.execute(ctx(config, Map.of("rate", 5)));
        assertTrue(result.isEmpty());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DSL table — tableExists false path (drop then verify)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("header mode with tables dropped: resolveRateFromDslTable returns null, identity rate used")
    void headerModeTablesDroppedFallsBackToIdentity() {
        // Temporarily drop the tables to exercise the tableExists=false path explicitly
        jdbcTemplate.execute("DROP TABLE IF EXISTS mt_fin_exchange_rate");
        jdbcTemplate.execute("DROP TABLE IF EXISTS mt_fin_currency");
        finTablesCreated = false;  // suppress cleanup attempt for missing tables

        String config = """
                {
                  "baseCurrencyField": "base_currency",
                  "currencyField": "doc_currency",
                  "rateField": "rate",
                  "amountFields": ["amount"]
                }
                """;
        Map<String, Object> payload = Map.of(
                "doc_currency", "USD",
                "amount", new BigDecimal("100.00")
        );

        Map<String, Object> result = handler.execute(ctx(config, payload));
        assertNotNull(result);
        // Table absent → resolveBaseCurrencyFromDslTable returns null → DEFAULT "CNY"
        assertEquals("CNY", result.get("base_currency"));
        // Table absent → identity rate → amount_base = 100.00
        assertEquals(new BigDecimal("100.00"), result.get("amount_base"));

        // Recreate for subsequent tests in this class run
        createFinTablesIfAbsent();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // stringList edge cases (via amountFields config)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("amountFields: non-list value in config → treated as empty list, no base amounts written")
    void amountFieldsNonListValue() {
        // stringList(Object) where value is not a List → returns List.of()
        String config = """
                {
                  "mode": "header",
                  "amountFields": "not_a_list"
                }
                """;
        Map<String, Object> result = handler.execute(ctx(config, Map.of("not_a_list", 100)));
        assertFalse(result.containsKey("not_a_list_base"));
    }

    @Test
    @DisplayName("amountFields: list with blank/null entries are filtered out")
    void amountFieldsWithBlankEntries() {
        String config = """
                {
                  "amountFields": ["  ", "", "real_amount"]
                }
                """;
        Map<String, Object> payload = Map.of("real_amount", new BigDecimal("50.00"));

        Map<String, Object> result = handler.execute(ctx(config, payload));
        assertEquals(new BigDecimal("50.00"), result.get("real_amount_base"));
        assertFalse(result.containsKey("_base"));
        assertFalse(result.containsKey(" _base"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // rateIdField: not written when rateResult has no rateId (identity path)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("header mode: rateIdField not written when identity rate is used (rateId=null)")
    void headerModeRateIdNotWrittenForIdentityRate() {
        String config = """
                {
                  "baseCurrencyField": "base_currency",
                  "rateField": "rate",
                  "rateIdField": "rate_id"
                }
                """;
        Map<String, Object> result = handler.execute(ctx(config, Map.of()));
        // Identity path: rateResult.getRateId() == null → putIfConfigured not called for rateIdField
        assertFalse(result.containsKey("rate_id"));
    }

    @Test
    @DisplayName("header mode: rateIdField written when DSL row has an id")
    void headerModeRateIdWrittenFromDslRow() {
        seedBaseCurrency("CNY");
        long rateId = seedRate("eur", "cny", new BigDecimal("7.80"), LocalDate.now());

        String config = """
                {
                  "baseCurrencyField": "base_currency",
                  "currencyField": "doc_currency",
                  "rateField": "rate",
                  "rateIdField": "rate_id"
                }
                """;
        Map<String, Object> payload = Map.of("doc_currency", "EUR");

        Map<String, Object> result = handler.execute(ctx(config, payload));
        assertTrue(result.containsKey("rate_id"), "rate_id should be present when DSL row found");
        assertEquals(rateId, ((Number) result.get("rate_id")).longValue());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // resolveRate: null from/to currency → identity
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("header mode: empty currency string in payload treated as null currency → same-currency path")
    void headerModeEmptyCurrencyString() {
        String config = """
                {
                  "baseCurrencyField": "base_currency",
                  "currencyField": "currency",
                  "rateField": "rate"
                }
                """;
        // Empty string → resolveCurrencyCode returns null → docCurrency = baseCurrency
        Map<String, Object> payload = Map.of("currency", "");

        Map<String, Object> result = handler.execute(ctx(config, payload));
        assertNotNull(result);
        assertEquals(BigDecimal.ONE, result.get("rate"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Multiple rate rows: latest-date wins
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("header mode: when multiple rate rows exist, most-recent effective date wins")
    void headerModeLatestRateWins() {
        seedBaseCurrency("CNY");
        // Older rate
        seedRate("usd", "cny", new BigDecimal("6.50"), LocalDate.now().minusDays(10));
        // Newer rate (today)
        long latestId = seedRate("usd", "cny", new BigDecimal("7.30"), LocalDate.now());

        String config = """
                {
                  "baseCurrencyField": "base_currency",
                  "currencyField": "doc_currency",
                  "rateField": "rate",
                  "rateIdField": "rate_id",
                  "amountFields": ["amount"]
                }
                """;
        Map<String, Object> payload = Map.of(
                "doc_currency", "USD",
                "amount", new BigDecimal("10.00")
        );

        Map<String, Object> result = handler.execute(ctx(config, payload));
        BigDecimal rate = (BigDecimal) result.get("rate");
        assertEquals(0, new BigDecimal("7.30").compareTo(rate),
                "Expected latest rate 7.30 but got: " + rate);
        assertEquals(latestId, ((Number) result.get("rate_id")).longValue());
        // 10 * 7.30 = 73.00
        assertEquals(new BigDecimal("73.00"), result.get("amount_base"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // resolveRate: future-dated rate not used (fin_exr_effective_date <= today)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("header mode: future-dated rate row is ignored, falls back to identity")
    void headerModeFutureDatedRateIgnored() {
        seedBaseCurrency("CNY");
        // Rate effective tomorrow — should NOT be used
        seedRate("usd", "cny", new BigDecimal("8.00"), LocalDate.now().plusDays(1));

        String config = """
                {
                  "baseCurrencyField": "base_currency",
                  "currencyField": "doc_currency",
                  "rateField": "rate"
                }
                """;
        Map<String, Object> payload = Map.of("doc_currency", "USD");

        Map<String, Object> result = handler.execute(ctx(config, payload));
        // Future rate excluded → no direct row → no inverse row → identity
        assertEquals(BigDecimal.ONE, result.get("rate"));
    }
}
