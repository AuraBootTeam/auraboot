package com.auraboot.module.finance.engine;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.framework.meta.service.CommandHandler;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Generic currency conversion handler for the command pipeline.
 * <p>
 * Invoked as a HANDLER-type BindingRule on CREATE/UPDATE commands.
 * Reads config to determine which fields to convert, looks up the exchange rate,
 * and computes base-currency amounts.
 * <p>
 * Supports two modes via config:
 * <ul>
 *   <li><b>header</b>: Document has its own currency_code field → look up rate</li>
 *   <li><b>line</b>: Line item inherits rate from parent document</li>
 * </ul>
 *
 * Config example (header):
 * <pre>
 * {
 *   "mode": "header",
 *   "currencyField": "sl_so_currency_code",
 *   "rateField": "sl_so_exchange_rate",
 *   "rateIdField": "sl_so_exchange_rate_id",
 *   "baseCurrencyField": "sl_so_base_currency_code",
 *   "amountFields": ["sl_so_total_amount"]
 * }
 * </pre>
 *
 * Config example (line):
 * <pre>
 * {
 *   "mode": "line",
 *   "parentModel": "sl_sales_order",
 *   "parentIdField": "sl_sol_order_id",
 *   "parentRateField": "sl_so_exchange_rate",
 *   "amountFields": ["sl_sol_amount", "sl_sol_price"]
 * }
 * </pre>
 *
 * @author AuraBoot Team
 * @since 6.4.0
 */
@Slf4j
@Component("currencyConversionHandler")
@RequiredArgsConstructor
public class CurrencyConversionHandler implements CommandHandler {

    private final CurrencyConversionService currencyConversionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TenantClock tenantClock;

    @Override
    public String getHandlerName() {
        return "currencyConversionHandler";
    }

    @Override
    public Map<String, Object> execute(CommandHandlerContext context) {
        Map<String, Object> config = parseConfig(context.getRuleConfig());
        String mode = (String) config.getOrDefault("mode", "header");

        Map<String, Object> payload = context.getPayload();
        if (payload == null) {
            payload = new HashMap<>();
        }

        Map<String, Object> updates = new HashMap<>();

        if ("header".equals(mode)) {
            executeHeaderConversion(config, payload, updates, context);
        } else if ("line".equals(mode)) {
            executeLineConversion(config, payload, updates, context);
        }

        return updates;
    }

    /**
     * Header mode: look up exchange rate from currency_code, compute _base amounts.
     */
    private void executeHeaderConversion(Map<String, Object> config,
                                          Map<String, Object> payload,
                                          Map<String, Object> updates,
                                          CommandHandlerContext context) {
        String currencyField = (String) config.get("currencyField");
        String rateField = (String) config.get("rateField");
        String rateIdField = (String) config.get("rateIdField");
        String baseCurrencyField = (String) config.get("baseCurrencyField");
        @SuppressWarnings("unchecked")
        List<String> amountFields = (List<String>) config.getOrDefault("amountFields", List.of());
        Long tenantId = context.getTenantId() != null ? context.getTenantId() : MetaContext.getCurrentTenantId();

        // Get document currency from payload
        Object currencyRef = payload.get(currencyField);
        String baseCurrency = currencyConversionService.getBaseCurrency(tenantId);
        updates.put(baseCurrencyField, baseCurrency);

        String docCurrency = resolveCurrencyCode(currencyRef);
        LocalDate docDate = resolveDate(payload);
        try {
            log.info("currencyConversionHandler header conversion: tenantId={}, docCurrency={}, baseCurrency={}, docDate={}, amountFields={}",
                    tenantId, docCurrency, baseCurrency, docDate, amountFields);

            if (docCurrency == null || docCurrency.equalsIgnoreCase(baseCurrency)) {
                // Same currency or no currency specified: rate = 1
                updates.put(rateField, BigDecimal.ONE);
                for (String field : amountFields) {
                    BigDecimal amount = toBigDecimal(payload.get(field));
                    if (amount != null) {
                        updates.put(field + "_base", amount);
                    }
                }
                return;
            }

            // Look up exchange rate
            CurrencyConversionService.ExchangeRateResult rateResult =
                    currencyConversionService.getRate(docCurrency, baseCurrency, docDate, "spot", tenantId);

            updates.put(rateField, rateResult.getRate());
            if (rateResult.getRateId() != null) {
                updates.put(rateIdField, rateResult.getRateId());
            }

            // Convert each amount field
            int baseScale = 2; // default, could be fetched from currency config
            for (String field : amountFields) {
                BigDecimal amount = toBigDecimal(payload.get(field));
                if (amount != null) {
                    BigDecimal baseAmount = amount.multiply(rateResult.getRate())
                            .setScale(baseScale, RoundingMode.HALF_UP);
                    updates.put(field + "_base", baseAmount);
                }
            }
        } catch (RuntimeException e) {
            log.error("currencyConversionHandler failed: tenantId={}, docCurrency={}, baseCurrency={}, docDate={}, payloadKeys={}, error={}",
                    tenantId, docCurrency, baseCurrency, docDate, payload.keySet(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Line mode: read exchange rate from parent document, compute _base amounts.
     */
    private void executeLineConversion(Map<String, Object> config,
                                        Map<String, Object> payload,
                                        Map<String, Object> updates,
                                        CommandHandlerContext context) {
        String parentModel = (String) config.get("parentModel");
        String parentIdField = (String) config.get("parentIdField");
        String parentRateField = (String) config.get("parentRateField");
        @SuppressWarnings("unchecked")
        List<String> amountFields = (List<String>) config.getOrDefault("amountFields", List.of());

        // Get parent record ID from payload
        Object parentIdObj = payload.get(parentIdField);
        if (parentIdObj == null) {
            log.debug("Parent ID field {} is null, skipping line conversion", parentIdField);
            return;
        }
        Long parentId = ((Number) parentIdObj).longValue();

        // Query parent record for exchange rate
        BigDecimal rate = queryParentRate(parentModel, parentRateField, parentId, context.getTenantId());
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            rate = BigDecimal.ONE; // fallback: same currency
        }

        int baseScale = 2;
        for (String field : amountFields) {
            BigDecimal amount = toBigDecimal(payload.get(field));
            if (amount != null) {
                BigDecimal baseAmount = amount.multiply(rate)
                        .setScale(baseScale, RoundingMode.HALF_UP);
                updates.put(field + "_base", baseAmount);
            }
        }
    }

    private BigDecimal queryParentRate(String parentModel, String rateField,
                                        Long parentId, Long tenantId) {
        String tableName = "mt_" + parentModel;
        String sql = "SELECT " + rateField + " FROM " + tableName
                + " WHERE id = ? AND tenant_id = ?";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, parentId, tenantId);
            if (!rows.isEmpty()) {
                return toBigDecimal(rows.get(0).get(rateField));
            }
        } catch (Exception e) {
            log.warn("Failed to query parent rate from {}: {}", tableName, e.getMessage());
        }
        return null;
    }

    private String resolveCurrencyCode(Object ref) {
        if (ref == null) return null;
        if (ref instanceof String s) return s;
        // If it's a number (reference ID), resolve via currency table
        try {
            Long currencyId = ((Number) ref).longValue();
            String sql = "SELECT fin_cur_code FROM mt_fin_currency WHERE id = ?";
            List<String> results = jdbcTemplate.queryForList(sql, String.class, currencyId);
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            return ref.toString();
        }
    }

    private LocalDate resolveDate(Map<String, Object> payload) {
        for (String dateField : List.of("document_date", "order_date", "entry_date",
                "created_at", "create_time")) {
            Object val = payload.get(dateField);
            if (val instanceof LocalDate ld) return ld;
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            return LocalDate.now();
        }
        return tenantClock.businessDate(tenantId);
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

    private Map<String, Object> parseConfig(String ruleConfig) {
        if (ruleConfig == null || ruleConfig.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(ruleConfig, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Failed to parse currency conversion handler config: {}", e.getMessage());
            return Map.of();
        }
    }
}
