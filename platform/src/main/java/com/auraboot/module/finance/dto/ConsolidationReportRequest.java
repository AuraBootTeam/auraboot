package com.auraboot.module.finance.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class ConsolidationReportRequest {

    /** 合并基准日期 */
    @NotNull
    private LocalDate reportingDate;

    /** 报告货币（ISO 4217），默认取主实体的功能货币 */
    private String reportingCurrency;

    /** 各实体的财务数据（由调用方从各自 DSL 模型中提取后传入） */
    private List<EntityFinancial> entityFinancials;

    @Data
    public static class EntityFinancial {
        /** 法人实体 id（对应 ab_legal_entity.id） */
        @NotNull
        private Long entityId;

        /** 收入（本位货币）*/
        private BigDecimal revenue = BigDecimal.ZERO;

        /** 费用（本位货币）*/
        private BigDecimal expenses = BigDecimal.ZERO;

        /** 资产（本位货币）*/
        private BigDecimal assets = BigDecimal.ZERO;

        /** 负债（本位货币）*/
        private BigDecimal liabilities = BigDecimal.ZERO;
    }
}
