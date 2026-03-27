package com.auraboot.framework.currency.spi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * SPI for currency conversion. Enterprise finance module provides the implementation.
 * Core consumers use {@code @Autowired(required = false)} for optional dependency.
 */
public interface CurrencyConversionSpi {

    ExchangeRateResult getRate(String fromCurrency, String toCurrency,
                               LocalDate date, String rateType);

    BigDecimal convert(BigDecimal amount, String fromCurrency, String toCurrency,
                       LocalDate date, String rateType);

    void convertRecord(Map<String, Object> record, List<String> amountFields,
                       String fromCurrencyField, String toCurrency,
                       LocalDate date, String rateType);

    String getBaseCurrency();

    String getBaseCurrency(Long tenantId);
}
