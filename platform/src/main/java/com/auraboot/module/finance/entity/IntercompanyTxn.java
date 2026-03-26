package com.auraboot.module.finance.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Internal intercompany transaction pending elimination in group consolidation.
 * Stored in {@code fin_intercompany_txn}.
 *
 * <p>Records sales, loans, dividends, and service charges between entities
 * within the same tenant (group). Transactions that have been eliminated from
 * the consolidated statements have {@code isEliminated = true}.
 */
@Data
@TableName("fin_intercompany_txn")
public class IntercompanyTxn {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** ULID public identifier. */
    private String pid;

    private Long tenantId;

    /** The entity that originated the transaction (seller / lender / service provider). */
    private Long fromEntityId;

    /** The entity that received the transaction (buyer / borrower / service recipient). */
    private Long toEntityId;

    private LocalDate txnDate;

    /**
     * Type of intercompany transaction.
     * Supported values: SALE, LOAN, DIVIDEND, SERVICE.
     */
    private String txnType;

    private BigDecimal amount;

    /** Currency of the transaction (ISO 4217). */
    private String currency;

    private String description;

    /**
     * Whether this transaction has been eliminated in a consolidation run.
     * Once set to true the transaction should not be eliminated again.
     */
    private Boolean isEliminated;

    private Instant createdAt;
}
