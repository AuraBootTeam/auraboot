package com.auraboot.module.finance.engine;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.TenantClock;
import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calculates realized exchange gain/loss during payment reconciliation
 * and generates corresponding journal entries.
 *
 * <p>When a payment settles an invoice denominated in a foreign currency,
 * the exchange rate at payment date typically differs from the rate at
 * invoice date. This difference produces a realized FX gain or loss:
 *
 * <pre>
 *   Invoice: currency=USD, rate=7.10, amount=1000 -&gt; base=7100
 *   Payment: currency=USD, rate=7.20, amount=1000 -&gt; base=7200
 *   Realized gain/loss = payment_base - invoice_base = +100 (gain)
 * </pre>
 *
 * <h3>AR (Receivable) scenario — gain when payment rate &gt; invoice rate:</h3>
 * <pre>
 *   DR Accounts Receivable  100   (reduce AR over-statement)
 *     CR Exchange Gain      100   (6061)
 * </pre>
 *
 * <h3>AP (Payable) scenario — the sign is reversed by the caller:</h3>
 * <p>For AP, a positive rate difference means we pay MORE in base currency = loss.
 * The caller should negate the result of {@link #calculateRealizedGainLoss} for AP.
 *
 * @author AuraBoot Team
 * @since 6.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeGainLossService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrencyConversionService currencyConversionService;
    private final TenantClock tenantClock;

    private static final int BASE_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** GL account code for exchange gain (6061 — Chinese GAAP standard). */
    private static final String GL_CODE_FX_GAIN = "6061";
    /** GL account code for exchange loss (6711 — Chinese GAAP standard). */
    private static final String GL_CODE_FX_LOSS = "6711";
    /** Default AR control account code — used as counterpart in AR FX entries. */
    private static final String GL_CODE_AR = "1122";
    /** Default AP control account code — used as counterpart in AP FX entries. */
    private static final String GL_CODE_AP = "2202";

    private static final String ACCOUNT_TABLE = "mt_fin_account";
    private static final String JE_TABLE = "mt_fin_journal_entry";
    private static final String JL_TABLE = "mt_fin_journal_line";
    private static final String PERIOD_TABLE = "mt_fin_fiscal_period";

    // ==================== Public API ====================

    /**
     * Calculate realized gain/loss on payment reconciliation.
     * Called when a payment is matched against an AR/AP transaction.
     *
     * <p>Reads exchange_rate from both invoice and payment records in their
     * respective {@code mt_{model}} tables. If exchange_rate columns
     * are not present, falls back to rate = 1.0 (same-currency assumption).
     *
     * @param invoiceModel  model code of the invoice/transaction (e.g. "fin_ar_transaction")
     * @param invoiceId     record ID of the invoice
     * @param paymentModel  model code of the payment (e.g. "fin_payment")
     * @param paymentId     record ID of the payment
     * @param settledAmount amount being settled (in document currency)
     * @param tenantId      tenant context
     * @return the gain/loss amount in base currency (positive = gain, negative = loss)
     */
    @Transactional(readOnly = true)
    public BigDecimal calculateRealizedGainLoss(
            String invoiceModel, Long invoiceId,
            String paymentModel, Long paymentId,
            BigDecimal settledAmount, Long tenantId) {

        if (settledAmount == null || settledAmount.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal invoiceRate = readExchangeRate(invoiceModel, invoiceId, tenantId);
        BigDecimal paymentRate = readExchangeRate(paymentModel, paymentId, tenantId);

        log.debug("FX gain/loss calc: invoiceRate={}, paymentRate={}, settledAmount={}",
                invoiceRate, paymentRate, settledAmount);

        if (invoiceRate.compareTo(paymentRate) == 0) {
            return BigDecimal.ZERO;
        }

        // settled_base at invoice time vs payment time
        BigDecimal invoiceBase = settledAmount.multiply(invoiceRate).setScale(BASE_SCALE, ROUNDING);
        BigDecimal paymentBase = settledAmount.multiply(paymentRate).setScale(BASE_SCALE, ROUNDING);

        // positive = gain (received/paid more base currency than originally booked)
        BigDecimal gainLoss = paymentBase.subtract(invoiceBase);

        log.info("Realized FX gain/loss: {} (invoiceBase={}, paymentBase={}, settled={}, "
                        + "invoice={}/#{}, payment=#{} )",
                gainLoss, invoiceBase, paymentBase, settledAmount,
                invoiceModel, invoiceId, paymentId);

        return gainLoss;
    }

    /**
     * Create a balanced journal entry for realized FX gain/loss.
     *
     * <p>Produces a 2-line journal entry. The counterpart account (AR or AP)
     * is derived from the {@code invoiceModel}:
     * <ul>
     *   <li>{@code fin_ar_transaction} → AR account (1122)</li>
     *   <li>{@code fin_ap_transaction} → AP account (2202)</li>
     * </ul>
     *
     * <h4>Gain (positive amount, AR scenario):</h4>
     * <pre>
     *   Line 1  DR  Accounts Receivable (1122)  100
     *   Line 2  CR  Exchange Gain (6061)         100
     * </pre>
     *
     * <h4>Loss (negative amount, AR scenario):</h4>
     * <pre>
     *   Line 1  DR  Exchange Loss (6711)         100
     *   Line 2  CR  Accounts Receivable (1122)   100
     * </pre>
     *
     * @param gainLossAmount positive = gain, negative = loss
     * @param invoiceModel   source document type (e.g. "fin_ar_transaction")
     * @param invoiceId      source document ID
     * @param paymentId      payment document ID
     * @param tenantId       tenant context
     * @return the created journal entry pid, or null if amount is zero
     */
    @Transactional
    public String createGainLossJournalEntry(
            BigDecimal gainLossAmount,
            String invoiceModel, Long invoiceId,
            Long paymentId, Long tenantId) {

        if (gainLossAmount == null || gainLossAmount.compareTo(BigDecimal.ZERO) == 0) {
            log.debug("Skipping FX journal entry: gain/loss is zero");
            return null;
        }

        BigDecimal absAmount = gainLossAmount.abs();
        boolean isGain = gainLossAmount.compareTo(BigDecimal.ZERO) > 0;

        // Resolve FX gain/loss GL account
        String fxGlCode = isGain ? GL_CODE_FX_GAIN : GL_CODE_FX_LOSS;
        String fxAccountPid = resolveAccountPid(fxGlCode, tenantId);
        if (fxAccountPid == null) {
            throw new BusinessException("GL account not found: " + fxGlCode
                    + " (" + (isGain ? "Exchange Gain" : "Exchange Loss") + "). "
                    + "Please add this account to the chart of accounts.");
        }

        // Resolve counterpart GL account (AR or AP)
        String counterpartGlCode = resolveCounterpartGlCode(invoiceModel);
        String counterpartAccountPid = resolveAccountPid(counterpartGlCode, tenantId);
        if (counterpartAccountPid == null) {
            throw new BusinessException("Counterpart GL account not found: " + counterpartGlCode
                    + ". Please add this account to the chart of accounts.");
        }

        // Resolve fiscal period
        String periodPid = resolveCurrentPeriodPid(tenantId);
        if (periodPid == null) {
            throw new BusinessException("No open fiscal period found for today"
                    + ". Cannot create FX gain/loss journal entry.");
        }

        String entryNo = generateEntryNo();
        String jePid = generatePid();
        LocalDate today = tenantClock.businessDate(MetaContext.getCurrentTenantId());
        Instant now = Instant.now();

        String sourceRef = invoiceModel + ":" + invoiceId;
        String memo = (isGain ? "Realized FX Gain" : "Realized FX Loss")
                + " — " + invoiceModel + "#" + invoiceId + " / payment#" + paymentId;

        // --- Insert journal entry header ---
        String jeInsert = "INSERT INTO " + JE_TABLE
                + " (pid, fin_je_entry_no, fin_je_period_id, fin_je_entry_date,"
                + "  fin_je_status, fin_je_source_type, fin_je_source_id, fin_je_memo,"
                + "  fin_je_total_debit, fin_je_total_credit,"
                + "  tenant_id, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 'draft', ?, ?, ?, ?, ?, ?, ?, ?)";

        jdbcTemplate.update(jeInsert,
                jePid, entryNo, periodPid, today,
                "fx_gain_loss", sourceRef,
                memo, absAmount, absAmount,
                tenantId, now, now);

        // --- Insert balanced journal lines ---
        if (isGain) {
            // DR counterpart (AR/AP)
            insertJournalLine(jePid, 1, counterpartAccountPid,
                    absAmount, BigDecimal.ZERO,
                    "FX Gain — DR " + counterpartGlCode, tenantId, now);
            // CR FX Gain
            insertJournalLine(jePid, 2, fxAccountPid,
                    BigDecimal.ZERO, absAmount,
                    "FX Gain — CR " + fxGlCode, tenantId, now);
        } else {
            // DR FX Loss
            insertJournalLine(jePid, 1, fxAccountPid,
                    absAmount, BigDecimal.ZERO,
                    "FX Loss — DR " + fxGlCode, tenantId, now);
            // CR counterpart (AR/AP)
            insertJournalLine(jePid, 2, counterpartAccountPid,
                    BigDecimal.ZERO, absAmount,
                    "FX Loss — CR " + counterpartGlCode, tenantId, now);
        }

        log.info("Created FX journal entry: pid={}, entryNo={}, amount={}, type={}, lines=2",
                jePid, entryNo, absAmount, isGain ? "gain" : "loss");

        return jePid;
    }

    // ==================== Internal Methods ====================

    /**
     * Read the exchange rate from a dynamic mt record.
     * Scans all columns for one ending with {@code _exchange_rate}.
     * Returns {@code 1.0} if no exchange rate column is found (same-currency assumption).
     */
    private BigDecimal readExchangeRate(String modelCode, Long recordId, Long tenantId) {
        String tableName = "mt_" + modelCode;
        String sql = "SELECT * FROM " + tableName + " WHERE id = ? AND tenant_id = ? LIMIT 1";

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, recordId, tenantId);
            if (rows.isEmpty()) {
                throw new BusinessException("Record not found: " + tableName + "#" + recordId);
            }

            Map<String, Object> record = rows.get(0);

            // Look for any column that represents an exchange rate
            for (String key : record.keySet()) {
                if (key.endsWith("_exchange_rate") || key.equals("exchange_rate")) {
                    BigDecimal rate = toBigDecimal(record.get(key));
                    if (rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
                        return rate;
                    }
                }
            }

            log.debug("No exchange_rate column in {}, assuming rate=1.0", tableName);
            return BigDecimal.ONE;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to read exchange rate from {}#{}: {}", tableName, recordId, e.getMessage());
            throw new BusinessException("Failed to read " + tableName + "#" + recordId + ": " + e.getMessage());
        }
    }

    /**
     * Determine the counterpart GL account code from the invoice model.
     * AR transactions use the AR control account; AP transactions use the AP control account.
     */
    private String resolveCounterpartGlCode(String invoiceModel) {
        if (invoiceModel != null && invoiceModel.contains("_ap_")) {
            return GL_CODE_AP;
        }
        // Default to AR for fin_ar_transaction or any unrecognized model
        return GL_CODE_AR;
    }

    /**
     * Resolve account pid from GL account code.
     */
    private String resolveAccountPid(String accountCode, Long tenantId) {
        String sql = "SELECT pid FROM " + ACCOUNT_TABLE
                + " WHERE fin_acc_code = ? AND tenant_id = ? LIMIT 1";
        try {
            List<String> results = jdbcTemplate.queryForList(sql, String.class, accountCode, tenantId);
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            log.warn("Failed to resolve account for code '{}': {}", accountCode, e.getMessage());
            return null;
        }
    }

    /**
     * Resolve the current fiscal period pid (OPEN status, containing today's date).
     */
    private String resolveCurrentPeriodPid(Long tenantId) {
        LocalDate today = tenantClock.businessDate(tenantId);
        String sql = "SELECT pid FROM " + PERIOD_TABLE
                + " WHERE tenant_id = ? AND fin_fp_start_date <= ? AND fin_fp_end_date >= ?"
                + " AND fin_fp_status = 'open'"
                + " ORDER BY fin_fp_start_date DESC LIMIT 1";
        try {
            List<String> results = jdbcTemplate.queryForList(sql, String.class, tenantId, today, today);
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            log.warn("Failed to resolve fiscal period for {}: {}", today, e.getMessage());
            return null;
        }
    }

    /**
     * Insert a single journal line into mt_fin_journal_line.
     */
    private void insertJournalLine(String entryPid, int lineNo, String accountPid,
                                    BigDecimal debit, BigDecimal credit,
                                    String memo, Long tenantId, Instant now) {
        String pid = generatePid();
        String sql = "INSERT INTO " + JL_TABLE
                + " (pid, fin_jl_entry_id, fin_jl_line_no, fin_jl_account_id,"
                + "  fin_jl_debit, fin_jl_credit, fin_jl_memo,"
                + "  fin_jl_aux_type, fin_jl_aux_id,"
                + "  tenant_id, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        jdbcTemplate.update(sql,
                pid, entryPid, lineNo, accountPid,
                debit, credit, memo,
                "fx_gain_loss", null,
                tenantId, now, now);
    }

    // ==================== Helpers ====================

    private static String generatePid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String generateEntryNo() {
        return "FX-" + tenantClock.businessDate(MetaContext.getCurrentTenantId())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
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
}
