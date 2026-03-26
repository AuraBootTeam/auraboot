package com.auraboot.module.finance.engine;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Period-end foreign currency revaluation service.
 *
 * <p>Implements IFRS IAS 21 / GAAP ASC 830 requirements:
 * retranslates foreign-currency monetary balances at the closing exchange
 * rate and records the resulting exchange gain/loss.
 *
 * <p>Supported balance models:
 * <ul>
 *   <li>AR (Accounts Receivable) — {@code mt_fin_ar_transaction}</li>
 *   <li>AP (Accounts Payable)    — {@code mt_fin_ap_transaction}</li>
 *   <li>Bank Account             — {@code mt_fin_bank_account}</li>
 * </ul>
 *
 * <p>For each qualifying record (currency != base currency, balance != 0):
 * <ol>
 *   <li>Fetch closing-date exchange rate via {@link CurrencyConversionService}</li>
 *   <li>Recalculate base-currency balance at new rate</li>
 *   <li>If adjustment != 0: update {@code base_amount} column on source record
 *       and insert a row into {@code fin_fx_revaluation_log}</li>
 * </ol>
 *
 * @author AuraBoot Team
 * @since 6.4.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FxRevaluationService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrencyConversionService currencyConversionService;

    private static final String LOG_TABLE = "fin_fx_revaluation_log";
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int AMOUNT_SCALE = 2;

    /**
     * Defines a balance model target for revaluation.
     */
    private record RevaluationTarget(
            String modelCode,
            String tableName,
            String currencyColumn,
            String amountColumn,
            String baseAmountColumn
    ) {}

    /**
     * Supported balance models.
     * If a table does not exist in the current installation, the revaluation
     * step for that model is skipped gracefully (logged at WARN level).
     */
    private static final List<RevaluationTarget> TARGETS = List.of(
            new RevaluationTarget(
                    "fin_ar_transaction",
                    "mt_fin_ar_transaction",
                    "fin_art_currency_code",
                    "fin_art_balance",
                    "fin_art_balance_base"
            ),
            new RevaluationTarget(
                    "fin_ap_transaction",
                    "mt_fin_ap_transaction",
                    "fin_apt_currency_code",
                    "fin_apt_balance",
                    "fin_apt_balance_base"
            ),
            new RevaluationTarget(
                    "fin_bank_account",
                    "mt_fin_bank_account",
                    "fin_ba_currency",
                    "fin_ba_balance",
                    null   // bank account has no separate base-amount column; skip base update
            )
    );

    // ==================== Public API ====================

    /**
     * Run period-end revaluation for the current tenant.
     *
     * @param reportingDate closing date for the revaluation (usually last day of the month)
     * @return summary of the revaluation run
     */
    @Transactional
    public RevaluationResult revaluate(LocalDate reportingDate) {
        if (reportingDate == null) {
            reportingDate = YearMonth.now().atEndOfMonth();
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        String baseCurrency = currencyConversionService.getBaseCurrency();

        log.info("Starting FX revaluation for tenant={}, date={}, baseCurrency={}",
                tenantId, reportingDate, baseCurrency);

        int totalAdjusted = 0;
        BigDecimal totalAdjustment = ZERO;

        for (RevaluationTarget target : TARGETS) {
            RevaluationModelResult result = revaluateModel(target, tenantId, baseCurrency, reportingDate);
            totalAdjusted += result.adjustedCount();
            totalAdjustment = totalAdjustment.add(result.totalAdjustment());
        }

        log.info("FX revaluation completed for tenant={}, date={}: {} records adjusted, total adjustment={}",
                tenantId, reportingDate, totalAdjusted, totalAdjustment);

        return new RevaluationResult(reportingDate, baseCurrency, totalAdjusted, totalAdjustment);
    }

    // ==================== Internal ====================

    private RevaluationModelResult revaluateModel(RevaluationTarget target,
                                                   Long tenantId,
                                                   String baseCurrency,
                                                   LocalDate reportingDate) {
        // Query all records with a foreign currency and non-zero balance
        String selectSql = buildSelectSql(target, tenantId);

        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(selectSql, tenantId);
        } catch (Exception e) {
            // Table may not exist if the finance plugin is not installed
            log.warn("Skipping revaluation for model '{}': unable to query table '{}' — {}",
                    target.modelCode(), target.tableName(), e.getMessage());
            return new RevaluationModelResult(0, ZERO);
        }

        int adjustedCount = 0;
        BigDecimal totalAdjustment = ZERO;
        List<Object[]> logBatch = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            Long recordId = toLong(row.get("id"));
            String currency = toStr(row.get("currency"));
            BigDecimal amount = toDecimal(row.get("amount"));
            BigDecimal originalBaseAmount = toDecimal(row.get("base_amount"));

            // Skip base-currency records
            if (currency == null || currency.equalsIgnoreCase(baseCurrency)) {
                continue;
            }
            // Skip zero-balance records
            if (amount == null || amount.compareTo(ZERO) == 0) {
                continue;
            }

            // Get closing-date exchange rate (foreign → base)
            CurrencyConversionService.ExchangeRateResult rateResult;
            try {
                rateResult = currencyConversionService.getRate(currency, baseCurrency, reportingDate, "accounting");
            } catch (Exception e) {
                log.warn("No exchange rate for {}/{} on {} — skipping record id={} in {}",
                        currency, baseCurrency, reportingDate, recordId, target.modelCode());
                continue;
            }

            BigDecimal newRate = rateResult.getRate();
            BigDecimal newBaseAmount = amount.multiply(newRate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
            BigDecimal adjustment = newBaseAmount.subtract(
                    originalBaseAmount != null ? originalBaseAmount : ZERO);

            // Skip if adjustment is negligible
            if (adjustment.abs().compareTo(BigDecimal.valueOf(0.005)) < 0) {
                continue;
            }

            // Update base_amount on source record (only if column exists for this model)
            if (target.baseAmountColumn() != null) {
                String updateSql = "UPDATE " + target.tableName()
                        + " SET " + target.baseAmountColumn() + " = ?"
                        + " WHERE id = ? AND tenant_id = ?";
                try {
                    jdbcTemplate.update(updateSql, newBaseAmount, recordId, tenantId);
                } catch (Exception e) {
                    log.error("Failed to update base amount for record id={} in {}: {}",
                            recordId, target.modelCode(), e.getMessage());
                    continue;
                }
            }

            // Accumulate log batch
            String pid = UniqueIdGenerator.generate();
            logBatch.add(new Object[]{
                    pid, tenantId, reportingDate, target.modelCode(), recordId,
                    currency,
                    amount != null ? amount : ZERO,
                    originalBaseAmount != null ? originalBaseAmount : ZERO,
                    newRate,
                    newBaseAmount,
                    adjustment
            });

            adjustedCount++;
            totalAdjustment = totalAdjustment.add(adjustment);
        }

        // Batch-insert log rows
        if (!logBatch.isEmpty()) {
            String insertSql = "INSERT INTO " + LOG_TABLE
                    + " (pid, tenant_id, revaluation_date, model_code, record_id,"
                    + "  currency, original_amount, original_base_amount,"
                    + "  new_rate, new_base_amount, adjustment)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try {
                jdbcTemplate.batchUpdate(insertSql, logBatch);
            } catch (Exception e) {
                log.error("Failed to insert revaluation log entries for model {}: {}",
                        target.modelCode(), e.getMessage());
            }
        }

        return new RevaluationModelResult(adjustedCount, totalAdjustment);
    }

    private String buildSelectSql(RevaluationTarget target, Long tenantId) {
        return "SELECT id,"
                + " " + target.currencyColumn() + " AS currency,"
                + " " + target.amountColumn() + " AS amount,"
                + (target.baseAmountColumn() != null
                    ? " " + target.baseAmountColumn() + " AS base_amount"
                    : " NULL::numeric AS base_amount")
                + " FROM " + target.tableName()
                + " WHERE tenant_id = ?"
                + " AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
    }

    // ==================== Helper converters ====================

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        return Long.valueOf(v.toString());
    }

    private static String toStr(Object v) {
        if (v == null) return null;
        return v.toString().trim();
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }

    // ==================== Result types ====================

    /**
     * Summary result of a full revaluation run.
     *
     * @param reportingDate   the closing date used for rate lookup
     * @param baseCurrency    tenant base currency code
     * @param adjustedCount   number of records where adjustment != 0
     * @param totalAdjustment net revaluation gain (positive) or loss (negative) in base currency
     */
    public record RevaluationResult(
            LocalDate reportingDate,
            String baseCurrency,
            int adjustedCount,
            BigDecimal totalAdjustment
    ) {}

    private record RevaluationModelResult(int adjustedCount, BigDecimal totalAdjustment) {}
}
