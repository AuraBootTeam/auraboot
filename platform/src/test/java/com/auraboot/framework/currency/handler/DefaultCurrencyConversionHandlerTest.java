package com.auraboot.framework.currency.handler;

import com.auraboot.framework.currency.dto.ConversionResult;
import com.auraboot.framework.currency.service.CurrencyService;
import com.auraboot.framework.currency.spi.CurrencyConversionSpi;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.meta.service.impl.pipeline.RecordSnapshotReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DefaultCurrencyConversionHandlerTest {

    @Mock ObjectProvider<CurrencyConversionSpi> spi;
    @Mock ObjectProvider<JdbcTemplate> jdbc;
    @Mock ObjectProvider<CurrencyService> platformCurrency;
    @Mock CurrencyService currencyService;
    @Mock RecordSnapshotReader snapshotReader;

    @Test
    void appliesPlatformRatesWithoutFinanceConfiguration() {
        var rate = new BigDecimal("7.2");
        when(platformCurrency.getIfAvailable()).thenReturn(currencyService);
        when(currencyService.convert(eq(BigDecimal.ONE), eq("usd"), eq("CNY"), any(LocalDate.class), eq(42L)))
                .thenReturn(new ConversionResult(BigDecimal.ONE, "usd", rate, "CNY", rate, LocalDate.now(), false));

        var result = handler().execute(context());

        assertThat(result).containsEntry("rate", rate)
                .containsEntry("base_currency", "CNY")
                .containsEntry("amount_base", new BigDecimal("72000.00"));
    }

    @Test
    void missingPlatformRateRetainsTheExistingIdentityFallback() {
        when(platformCurrency.getIfAvailable()).thenReturn(currencyService);
        when(currencyService.convert(eq(BigDecimal.ONE), eq("usd"), eq("CNY"), any(LocalDate.class), eq(42L)))
                .thenThrow(new CurrencyService.MissingExchangeRateException("no applicable rate"));

        assertThat(handler().execute(context())).containsEntry("rate", BigDecimal.ONE)
                .containsEntry("amount_base", new BigDecimal("10000.00"));
    }

    @Test
    void anAmountOnlyUpdateRetainsTheTargetCurrency() {
        var rate = new BigDecimal("7.8");
        when(snapshotReader.readRecordSnapshot(42L, "opportunity", "target"))
                .thenReturn(Map.of("currency", "eur", "amount", 5000));
        when(platformCurrency.getIfAvailable()).thenReturn(currencyService);
        when(currencyService.convert(eq(BigDecimal.ONE), eq("eur"), eq("CNY"), any(LocalDate.class), eq(42L)))
                .thenReturn(new ConversionResult(BigDecimal.ONE, "eur", rate, "CNY", rate, LocalDate.now(), false));
        var context = context();
        context.setOperationType("update");
        context.setTargetRecordId("target");
        context.setModelCode("opportunity");
        context.setPayload(Map.of("amount", 6000));

        assertThat(handler().execute(context)).containsEntry("rate", rate)
                .containsEntry("amount_base", new BigDecimal("46800.00"));
    }

    @Test
    void unrelatedUpdatesDoNotRefreshArithmeticOrLookupTheRecord() {
        var context = context();
        context.setOperationType("update");
        context.setPayload(Map.of("name", "Updated opportunity name"));

        assertThat(handler().execute(context)).isEmpty();
        verifyNoInteractions(snapshotReader, platformCurrency);
    }

    private DefaultCurrencyConversionHandler handler() {
        return new DefaultCurrencyConversionHandler(new ObjectMapper(), spi, jdbc, platformCurrency, snapshotReader);
    }

    private static CommandHandlerContext context() {
        return CommandHandlerContext.builder()
                .tenantId(42L)
                .payload(Map.of("currency", "usd", "amount", 10000))
                .ruleConfig("""
                        {"currencyField":"currency","rateField":"rate",
                         "baseCurrencyField":"base_currency","amountFields":["amount"]}
                        """)
                .build();
    }
}
