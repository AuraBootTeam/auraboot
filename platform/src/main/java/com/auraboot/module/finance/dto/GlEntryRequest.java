package com.auraboot.module.finance.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single debit or credit line in a journal entry request.
 * Each journal post must contain at least two lines and the
 * sum of debit_amount must equal the sum of credit_amount.
 */
@Data
public class GlEntryRequest {

    /**
     * Chart of Accounts code (e.g. "1001" for Cash, "6001" for Revenue).
     * Required.
     */
    private String accountCode;

    /**
     * Human-readable account name. Stored for reporting convenience.
     * Optional; if omitted the system stores accountCode as the name.
     */
    private String accountName;

    /**
     * Debit amount. Exactly one of debitAmount / creditAmount must be > 0.
     */
    private BigDecimal debitAmount;

    /**
     * Credit amount. Exactly one of debitAmount / creditAmount must be > 0.
     */
    private BigDecimal creditAmount;

    /**
     * ISO currency code (default CNY).
     */
    private String currency;

    /**
     * Line description / narrative.
     */
    private String description;

    /**
     * Effective date for this entry line.
     */
    private LocalDate entryDate;

    /**
     * Originating document type (e.g. PURCHASE_ORDER, SALES_ORDER, EXPENSE_CLAIM).
     */
    private String referenceType;

    /**
     * Originating document PID.
     */
    private String referencePid;
}
