package com.auraboot.module.finance.dto;

import com.auraboot.module.finance.entity.IntercompanyTxn;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class ConsolidationReportResult {

    private LocalDate reportingDate;
    private String reportingCurrency;

    // ── 合并损益表 ──
    /** 合并收入（消除内部交易后，按报告货币） */
    private BigDecimal consolidatedRevenue;
    /** 合并费用（消除内部交易后，按报告货币） */
    private BigDecimal consolidatedExpenses;
    /** 合并净利润 = consolidatedRevenue − consolidatedExpenses */
    private BigDecimal consolidatedNetIncome;

    // ── 合并资产负债表 ──
    /** 合并资产（按报告货币） */
    private BigDecimal consolidatedAssets;
    /** 合并负债（按报告货币） */
    private BigDecimal consolidatedLiabilities;
    /** 合并权益 = consolidatedAssets − consolidatedLiabilities */
    private BigDecimal consolidatedEquity;

    // ── 内部交易消除明细 ──
    /** 本次运行中被消除的内部交易列表 */
    private List<IntercompanyTxn> eliminatedTransactions;
    /** 内部交易消除总额（按报告货币） */
    private BigDecimal totalEliminatedAmount;

    // ── 参与实体 ──
    /** 本次合并涉及的法人实体数量 */
    private int entityCount;
}
