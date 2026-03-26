package com.auraboot.module.finance.engine;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.module.finance.dto.GlEntryRequest;
import com.auraboot.module.finance.mapper.GlEntryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * General Ledger service providing double-entry bookkeeping over {@code biz_gl_entry}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Post balanced journal entries (debit total must equal credit total)</li>
 *   <li>Query account balances for date ranges</li>
 *   <li>Generate trial balance for a fiscal period</li>
 *   <li>Provide paginated general ledger drill-down per account</li>
 * </ul>
 *
 * <p>This service operates on {@code biz_gl_entry} independently from the existing
 * {@code fac_journal_entry}/{@code fac_journal_line} DSL models (which handle voucher templates).
 * They serve different purposes: the fac_ models are business-document oriented,
 * whereas biz_gl_entry is a flat accounting sub-ledger for aggregation/reporting.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlService {

    private final GlEntryMapper mapper;
    private final CurrencyConversionService currencyConversionService;

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * Post a balanced double-entry journal.
     * Validates that the sum of all debit amounts equals the sum of all credit amounts.
     *
     * @param entries    list of entry lines; each line carries either a debit or credit amount
     * @param description optional journal description
     * @return generated journal PID shared by all entry lines in this post
     * @throws IllegalArgumentException if the journal does not balance or entries list is empty
     */
    @Transactional
    public String postJournalEntry(List<GlEntryRequest> entries, String description) {
        if (entries == null || entries.size() < 2) {
            throw new IllegalArgumentException("A journal entry requires at least two lines");
        }

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (GlEntryRequest e : entries) {
            BigDecimal debit  = e.getDebitAmount()  != null ? e.getDebitAmount()  : BigDecimal.ZERO;
            BigDecimal credit = e.getCreditAmount() != null ? e.getCreditAmount() : BigDecimal.ZERO;

            if (debit.compareTo(BigDecimal.ZERO) < 0 || credit.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException(
                        "Debit and credit amounts must be non-negative for account: " + e.getAccountCode());
            }
            if (debit.compareTo(BigDecimal.ZERO) > 0 && credit.compareTo(BigDecimal.ZERO) > 0) {
                throw new IllegalArgumentException(
                        "A line cannot have both debit and credit amounts for account: " + e.getAccountCode());
            }

            totalDebit  = totalDebit.add(debit);
            totalCredit = totalCredit.add(credit);
        }

        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new IllegalArgumentException(
                    "Journal is not balanced: total debit=" + totalDebit + ", total credit=" + totalCredit);
        }

        String journalPid = UniqueIdGenerator.generate();
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId   = MetaContext.getCurrentUserId();

        for (GlEntryRequest e : entries) {
            String entryPid = UniqueIdGenerator.generate();
            LocalDate entryDate = e.getEntryDate() != null ? e.getEntryDate() : LocalDate.now();
            String fiscalPeriod = entryDate.format(PERIOD_FMT);
            BigDecimal debit  = e.getDebitAmount()  != null ? e.getDebitAmount()  : BigDecimal.ZERO;
            BigDecimal credit = e.getCreditAmount() != null ? e.getCreditAmount() : BigDecimal.ZERO;
            String currency   = e.getCurrency() != null ? e.getCurrency() : currencyConversionService.getBaseCurrency();
            String entryDesc  = e.getDescription() != null ? e.getDescription() : description;
            String acctName   = e.getAccountName() != null ? e.getAccountName() : e.getAccountCode();

            mapper.insert(entryPid, tenantId, journalPid,
                    e.getAccountCode(), acctName,
                    debit, credit, currency, entryDesc,
                    entryDate, fiscalPeriod,
                    e.getReferenceType(), e.getReferencePid(),
                    userId);
        }

        log.info("Posted GL journal: journalPid={} lines={} totalDebit={}",
                journalPid, entries.size(), totalDebit);
        return journalPid;
    }

    /**
     * Get debit/credit/net balance for a single account between two dates.
     *
     * @param accountCode chart-of-accounts code
     * @param fromDate    inclusive start date
     * @param toDate      inclusive end date
     * @return map containing account_code, total_debit, total_credit, net_balance
     */
    public Map<String, Object> getAccountBalance(String accountCode,
                                                  LocalDate fromDate,
                                                  LocalDate toDate) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, Object> balance = mapper.accountBalance(tenantId, accountCode, fromDate, toDate);
        if (balance == null) {
            return Map.of("account_code", accountCode,
                          "total_debit", BigDecimal.ZERO,
                          "total_credit", BigDecimal.ZERO,
                          "net_balance", BigDecimal.ZERO);
        }
        return balance;
    }

    /**
     * Generate a trial balance for the given fiscal period (YYYY-MM).
     * Returns one row per account with total debits, credits, and net balance.
     *
     * @param fiscalPeriod YYYY-MM format period string
     * @return list of account balance rows
     */
    public List<Map<String, Object>> getTrialBalance(String fiscalPeriod) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return mapper.trialBalance(tenantId, fiscalPeriod);
    }

    /**
     * Retrieve paginated general ledger detail for an account.
     * Each row is a single GL entry line with running context.
     *
     * @param accountCode chart-of-accounts code
     * @param fromDate    inclusive start date
     * @param toDate      inclusive end date
     * @param pageNum     1-based page number
     * @param pageSize    rows per page (max 500)
     */
    public PaginationResult<Map<String, Object>> getGeneralLedger(String accountCode,
                                                                    LocalDate fromDate,
                                                                    LocalDate toDate,
                                                                    int pageNum,
                                                                    int pageSize) {
        if (pageNum < 1) pageNum = 1;
        if (pageSize < 1 || pageSize > 500) pageSize = 50;

        Long tenantId = MetaContext.getCurrentTenantId();
        int offset = (pageNum - 1) * pageSize;

        List<Map<String, Object>> rows = mapper.generalLedger(tenantId, accountCode, fromDate, toDate, pageSize, offset);
        long total = mapper.countByAccount(tenantId, accountCode, fromDate, toDate);

        return PaginationResult.of(rows, total, pageNum, pageSize);
    }
}
