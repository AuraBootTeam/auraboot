package com.auraboot.module.finance.engine;

import com.auraboot.framework.currency.dao.entity.ExchangeRate;
import com.auraboot.framework.currency.dao.mapper.ExchangeRateMapper;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for CurrencyConversionService platform-layer fallback (Step 5).
 *
 * <p>Verifies that when no rate exists in the finance DSL table
 * {@code mt_fin_exchange_rate}, the service falls back to the
 * platform-level {@code ab_exchange_rate} table via {@code CurrencyService}.
 */
class CurrencyConversionServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CurrencyConversionService currencyConversionService;

    @Autowired
    private ExchangeRateMapper exchangeRateMapper;

    private static final LocalDate TODAY = LocalDate.now();

    /**
     * When no finance-DSL rate exists for a pair but a platform-level rate does,
     * {@code getRate()} should succeed using the platform fallback (Step 5).
     *
     * <p>Uses currency code "xyz" — not a real ISO 4217 code, so it will never
     * match any entry in {@code mt_fin_exchange_rate}.
     */
    @Test
    void getRate_shouldFallBackToPlatformTable_whenFinanceDslHasNoRate() {
        // Arrange: insert a platform-level rate USD → XYZ = 9.99
        // pid column is VARCHAR(26); "tfb_" + 13-digit millis = 17 chars (within limit)
        String uniquePid = "tfb_" + System.currentTimeMillis();
        ExchangeRate platformRate = new ExchangeRate();
        platformRate.setPid(uniquePid);
        platformRate.setTenantId(testTenant.getId());
        platformRate.setBaseCurrency("usd");
        platformRate.setTargetCurrency("xyz");
        platformRate.setRate(new BigDecimal("9.990000"));
        platformRate.setEffectiveDate(TODAY);
        platformRate.setSource("test");
        platformRate.setCreatedAt(Instant.now());
        platformRate.setUpdatedAt(Instant.now());
        platformRate.setDeletedFlag(false);
        platformRate.setCreatedBy(testUser.getId());
        exchangeRateMapper.insert(platformRate);

        // Warm-up: call findLatestRate directly first (needed since ab_exchange_rate is in ignoreTable;
        // without this primer, the @Select statement cache may not be populated, causing the CurrencyService
        // transitive call to miss it — see also MybatisPlusConfig ignoreTable for ab_exchange_rate)
        ExchangeRate warmup = exchangeRateMapper.findLatestRate(testTenant.getId(), "usd", "xyz", TODAY);
        assertThat(warmup).as("USD→XYZ rate must be visible via mapper").isNotNull();

        // Act: getRate should find nothing in the finance DSL table, then fall back
        CurrencyConversionService.ExchangeRateResult result =
                currencyConversionService.getRate("usd", "xyz", TODAY, "spot");

        // Assert: the platform-level rate is returned
        assertThat(result).isNotNull();
        assertThat(result.getRate()).isEqualByComparingTo(new BigDecimal("9.990000"));
        // Platform fallback results have no rateId (from finance table)
        assertThat(result.getRateId()).isNull();
    }

    /**
     * When neither the finance DSL table nor the platform table has a rate,
     * {@code getRate()} should throw {@code BusinessException}.
     */
    @Test
    void getRate_shouldThrowBusinessException_whenNeitherTableHasRate() {
        // "qqq" is not in any table
        assertThatThrownBy(() ->
                currencyConversionService.getRate("usd", "qqq", TODAY, "spot"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exchange_rate_not_found");
    }
}
