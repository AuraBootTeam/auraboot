package com.auraboot.framework.currency.service;

import com.auraboot.module.finance.engine.CurrencyConversionService;
import com.auraboot.framework.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReportingCurrencyConverter}.
 * Uses Mockito to isolate from the CurrencyConversionService dependency.
 */
@ExtendWith(MockitoExtension.class)
class ReportingCurrencyConverterTest {

    @Mock
    private CurrencyConversionService currencyConversionService;

    @InjectMocks
    private ReportingCurrencyConverter converter;

    private static final BigDecimal RATE_CNY_TO_USD = new BigDecimal("0.138000");

    @BeforeEach
    void setUp() {
        // Default: CNY→USD rate = 0.138
        lenient().when(currencyConversionService.getRate(
                eq("cny"), eq("usd"), any(LocalDate.class), eq("spot")))
                .thenReturn(new CurrencyConversionService.ExchangeRateResult(RATE_CNY_TO_USD, 1L, false));
    }

    // ==================== Happy path ====================

    @Test
    void convert_withReportingCurrency_addsReportingFields() {
        // Arrange
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("crm_opp_expected_amount_base", new BigDecimal("10000.00"));
        row.put("crm_opp_discount_amount_base", new BigDecimal("500.00"));
        row.put("crm_opp_name", "Deal A");  // non-money field — must NOT be touched
        rows.add(row);

        // Act
        converter.convert(rows, "usd", "cny");

        // Assert: _reporting fields added
        assertThat(row).containsKey("crm_opp_expected_amount_reporting");
        assertThat(row).containsKey("crm_opp_discount_amount_reporting");

        BigDecimal expectedConverted = new BigDecimal("10000.00")
                .multiply(RATE_CNY_TO_USD)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        assertThat(row.get("crm_opp_expected_amount_reporting"))
                .isEqualTo(expectedConverted);

        // Assert: original _base fields unchanged
        assertThat(row.get("crm_opp_expected_amount_base")).isEqualTo(new BigDecimal("10000.00"));

        // Assert: non-money field not modified and no spurious _reporting field
        assertThat(row).containsKey("crm_opp_name");
        assertThat(row).doesNotContainKey("crm_opp_name_reporting");
    }

    @Test
    void convert_multipleRows_allRowsConverted() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("amount_base", new BigDecimal(i * 1000));
            rows.add(row);
        }

        converter.convert(rows, "usd", "cny");

        for (int i = 0; i < 3; i++) {
            assertThat(rows.get(i)).containsKey("amount_reporting");
        }
    }

    // ==================== No-op cases ====================

    @Test
    void convert_withNullReportingCurrency_noOp() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("amount_base", new BigDecimal("5000.00"));
        rows.add(row);

        converter.convert(rows, null, "cny");

        // No _reporting field added, rate service never called
        assertThat(row).doesNotContainKey("amount_reporting");
        verifyNoInteractions(currencyConversionService);
    }

    @Test
    void convert_withBlankReportingCurrency_noOp() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("amount_base", new BigDecimal("5000.00"));
        rows.add(row);

        converter.convert(rows, "   ", "cny");

        assertThat(row).doesNotContainKey("amount_reporting");
        verifyNoInteractions(currencyConversionService);
    }

    @Test
    void convert_withSameCurrency_noOp() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("amount_base", new BigDecimal("5000.00"));
        rows.add(row);

        // reportingCurrency == baseCurrency → identity, skip
        converter.convert(rows, "cny", "cny");

        assertThat(row).doesNotContainKey("amount_reporting");
        verifyNoInteractions(currencyConversionService);
    }

    @Test
    void convert_withNullRows_noOp() {
        // Should not throw
        assertThatCode(() -> converter.convert(null, "usd", "cny"))
                .doesNotThrowAnyException();
        verifyNoInteractions(currencyConversionService);
    }

    @Test
    void convert_withEmptyRows_noOp() {
        assertThatCode(() -> converter.convert(List.of(), "usd", "cny"))
                .doesNotThrowAnyException();
        verifyNoInteractions(currencyConversionService);
    }

    // ==================== Fault tolerance ====================

    @Test
    void convert_withNoRate_skipsGracefully() {
        // Rate lookup throws BusinessException (no rate configured)
        when(currencyConversionService.getRate(
                eq("cny"), eq("usd"), any(LocalDate.class), eq("spot")))
                .thenThrow(new BusinessException("EXCHANGE_RATE_NOT_FOUND: No rate for CNY→USD"));

        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("amount_base", new BigDecimal("5000.00"));
        rows.add(row);

        // Must NOT throw; _reporting field silently omitted
        assertThatCode(() -> converter.convert(rows, "usd", "cny"))
                .doesNotThrowAnyException();

        assertThat(row).doesNotContainKey("amount_reporting");
        // Original value untouched
        assertThat(row.get("amount_base")).isEqualTo(new BigDecimal("5000.00"));
    }

    @Test
    void convert_withNullAmountValue_fieldSkipped() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("amount_base", null);   // null value — not a Number, should be skipped
        row.put("real_amount_base", new BigDecimal("200.00"));
        rows.add(row);

        converter.convert(rows, "usd", "cny");

        // null field: no _reporting entry
        assertThat(row).doesNotContainKey("amount_reporting");
        // valid field: converted
        assertThat(row).containsKey("real_amount_reporting");
    }

    @Test
    void convert_withStringAmountValue_fieldSkipped() {
        // _base field that accidentally holds a non-numeric string must be skipped safely
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("amount_base", "not-a-number");
        rows.add(row);

        assertThatCode(() -> converter.convert(rows, "usd", "cny"))
                .doesNotThrowAnyException();

        assertThat(row).doesNotContainKey("amount_reporting");
    }
}
