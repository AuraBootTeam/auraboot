package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Reconciliation profile entity.
 * Defines matching rules between two data sources (internal vs external).
 * Supports SUPPLIER, BANK, and INTERCOMPANY reconciliation types.
 */
@Data
@TableName("ab_reconciliation_profile")
public class ReconciliationProfile {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("profile_code")
    private String profileCode;

    @TableField("profile_name")
    private String profileName;

    /** SUPPLIER, BANK, INTERCOMPANY */
    @TableField("profile_type")
    private String profileType;

    @TableField("description")
    private String description;

    // -- Source A configuration (internal records) --

    @TableField("source_a_model")
    private String sourceAModel;

    @TableField("source_a_amount_field")
    private String sourceAAmountField;

    @TableField("source_a_date_field")
    private String sourceADateField;

    @TableField("source_a_ref_field")
    private String sourceARefField;

    // -- Source B configuration (external records) --

    @TableField("source_b_model")
    private String sourceBModel;

    @TableField("source_b_amount_field")
    private String sourceBAmountField;

    @TableField("source_b_date_field")
    private String sourceBDateField;

    @TableField("source_b_ref_field")
    private String sourceBRefField;

    // -- Matching rules --

    @TableField("amount_tolerance")
    private BigDecimal amountTolerance;

    @TableField("date_tolerance_days")
    private Integer dateToleranceDays;

    @TableField("match_by_reference")
    private Boolean matchByReference;

    @TableField("match_by_amount")
    private Boolean matchByAmount;

    @TableField("match_by_date")
    private Boolean matchByDate;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;

    @TableLogic
    @TableField("deleted_flag")
    private Boolean deletedFlag;
}
