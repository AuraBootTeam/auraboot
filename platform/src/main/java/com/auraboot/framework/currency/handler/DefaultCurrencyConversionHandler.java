package com.auraboot.framework.currency.handler;

import com.auraboot.framework.currency.spi.CurrencyConversionSpi;
import com.auraboot.framework.currency.spi.ExchangeRateResult;
import com.auraboot.framework.meta.service.CommandHandler;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default command-pipeline currency handler for OSS/dev runtimes.
 *
 * <p>The commercial finance module may provide the same bean name with real
 * currency master data and exchange-rate lookup. This fallback keeps shared
 * sales/procurement plugin configs executable when that module is absent.
 */
@Slf4j
@Component("defaultCurrencyConversionHandler")
@RequiredArgsConstructor
public class DefaultCurrencyConversionHandler implements CommandHandler {

    private static final String DEFAULT_BASE_CURRENCY = "CNY";
    private static final int DEFAULT_SCALE = 2;

    private final ObjectMapper objectMapper;
    private final ObjectProvider<CurrencyConversionSpi> currencyConversionSpiProvider;

    @Override
    public String getHandlerName() {
        return "currencyConversionHandler";
    }

    @Override
    public Map<String, Object> execute(CommandHandlerContext context) {
        Map<String, Object> config = parseConfig(context.getRuleConfig());
        String mode = asText(config.getOrDefault("mode", "header"));
        Map<String, Object> payload = context.getPayload() != null ? context.getPayload() : Map.of();
        Map<String, Object> updates = new HashMap<>();

        if ("line".equals(mode)) {
            executeLineConversion(config, payload, updates);
        } else {
            executeHeaderConversion(config, payload, updates, context.getTenantId());
        }

        return updates;
    }

    private void executeHeaderConversion(Map<String, Object> config,
                                         Map<String, Object> payload,
                                         Map<String, Object> updates,
                                         Long tenantId) {
        String currencyField = asText(config.get("currencyField"));
        String rateField = asText(config.get("rateField"));
        String rateIdField = asText(config.get("rateIdField"));
        String baseCurrencyField = asText(config.get("baseCurrencyField"));
        List<String> amountFields = stringList(config.get("amountFields"));

        String baseCurrency = resolveBaseCurrency(tenantId);
        putIfConfigured(updates, baseCurrencyField, baseCurrency);

        String docCurrency = resolveCurrencyCode(currencyField != null ? payload.get(currencyField) : null);
        if (docCurrency == null) {
            docCurrency = baseCurrency;
        }

        ExchangeRateResult rateResult = resolveRate(docCurrency, baseCurrency);
        BigDecimal rate = rateResult != null && rateResult.getRate() != null
                ? rateResult.getRate()
                : BigDecimal.ONE;
        putIfConfigured(updates, rateField, rate);
        if (rateResult != null && rateResult.getRateId() != null) {
            putIfConfigured(updates, rateIdField, rateResult.getRateId());
        }

        applyBaseAmounts(payload, updates, amountFields, rate);
    }

    private void executeLineConversion(Map<String, Object> config,
                                       Map<String, Object> payload,
                                       Map<String, Object> updates) {
        String parentRateField = asText(config.get("parentRateField"));
        List<String> amountFields = stringList(config.get("amountFields"));

        BigDecimal rate = toBigDecimal(parentRateField != null ? payload.get(parentRateField) : null);
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            rate = BigDecimal.ONE;
        }
        applyBaseAmounts(payload, updates, amountFields, rate);
    }

    private ExchangeRateResult resolveRate(String fromCurrency, String toCurrency) {
        if (fromCurrency == null || toCurrency == null || fromCurrency.equalsIgnoreCase(toCurrency)) {
            return ExchangeRateResult.identity();
        }

        CurrencyConversionSpi spi = currencyConversionSpiProvider.getIfAvailable();
        if (spi == null) {
            log.debug("currencyConversionHandler: no CurrencyConversionSpi for {}->{}, using identity rate",
                    fromCurrency, toCurrency);
            return ExchangeRateResult.identity();
        }

        try {
            ExchangeRateResult result = spi.getRate(fromCurrency, toCurrency, LocalDate.now(), "spot");
            return result != null ? result : ExchangeRateResult.identity();
        } catch (RuntimeException e) {
            log.warn("currencyConversionHandler: rate lookup failed for {}->{}: {}; using identity rate",
                    fromCurrency, toCurrency, e.getMessage());
            return ExchangeRateResult.identity();
        }
    }

    private String resolveBaseCurrency(Long tenantId) {
        CurrencyConversionSpi spi = currencyConversionSpiProvider.getIfAvailable();
        if (spi == null) {
            return DEFAULT_BASE_CURRENCY;
        }
        try {
            String baseCurrency = tenantId != null ? spi.getBaseCurrency(tenantId) : spi.getBaseCurrency();
            return baseCurrency != null && !baseCurrency.isBlank() ? baseCurrency : DEFAULT_BASE_CURRENCY;
        } catch (RuntimeException e) {
            log.debug("currencyConversionHandler: base currency lookup failed: {}", e.getMessage());
            return DEFAULT_BASE_CURRENCY;
        }
    }

    private void applyBaseAmounts(Map<String, Object> payload,
                                  Map<String, Object> updates,
                                  List<String> amountFields,
                                  BigDecimal rate) {
        for (String field : amountFields) {
            BigDecimal amount = toBigDecimal(payload.get(field));
            if (amount != null) {
                updates.put(field + "_base", amount.multiply(rate).setScale(DEFAULT_SCALE, RoundingMode.HALF_UP));
            }
        }
    }

    private Map<String, Object> parseConfig(String ruleConfig) {
        if (ruleConfig == null || ruleConfig.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(ruleConfig, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("currencyConversionHandler: failed to parse config: {}", e.getMessage());
            return Map.of();
        }
    }

    private static void putIfConfigured(Map<String, Object> updates, String field, Object value) {
        if (field != null && !field.isBlank()) {
            updates.put(field, value);
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(DefaultCurrencyConversionHandler::asText)
                .filter(text -> text != null && !text.isBlank())
                .toList();
    }

    private static String resolveCurrencyCode(Object ref) {
        if (ref == null) {
            return null;
        }
        String text = ref.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
