package com.auraboot.framework.currency.spi;

import java.math.BigDecimal;

/**
 * Result of an exchange rate lookup.
 */
public record ExchangeRateResult(BigDecimal rate, Long rateId, boolean derived) {

    public static ExchangeRateResult identity() {
        return new ExchangeRateResult(BigDecimal.ONE, null, false);
    }

    public BigDecimal getRate() { return rate; }
    public Long getRateId() { return rateId; }
}
