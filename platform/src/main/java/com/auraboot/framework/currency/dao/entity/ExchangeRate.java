package com.auraboot.framework.currency.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Exchange rate entity for multi-currency support.
 * Stores historical and current rates between currency pairs.
 */
@Data
@TableName("ab_exchange_rate")
public class ExchangeRate {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;

    private Long tenantId;

    /** ISO 4217 base currency code (e.g. USD, CNY, EUR) */
    private String baseCurrency;

    /** ISO 4217 target currency code */
    private String targetCurrency;

    /** Exchange rate: 1 unit of base = rate units of target */
    private BigDecimal rate;

    /** Date this rate is effective */
    private LocalDate effectiveDate;

    /** Rate source: MANUAL, ECB, OPENEXCHANGE */
    private String source;

    private Instant createdAt;
    private Instant updatedAt;

    private Boolean deletedFlag = false;
    private Long createdBy;
    private Long updatedBy;
}
