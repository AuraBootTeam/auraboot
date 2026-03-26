package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Reconciliation item entity.
 * Represents a single matched/unmatched pair within a reconciliation run.
 * Match status: MATCHED, UNMATCHED_A, UNMATCHED_B, DISCREPANCY
 * Resolution: null (pending), APPROVED, ADJUSTED, WRITTEN_OFF
 */
@Data
@TableName("ab_reconciliation_item")
public class ReconciliationItem {

    public static final String MATCH_MATCHED = "matched";
    public static final String MATCH_UNMATCHED_A = "unmatched_a";
    public static final String MATCH_UNMATCHED_B = "unmatched_b";
    public static final String MATCH_DISCREPANCY = "discrepancy";

    public static final String RESOLUTION_APPROVED = "approved";
    public static final String RESOLUTION_ADJUSTED = "adjusted";
    public static final String RESOLUTION_WRITTEN_OFF = "written_off";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("run_id")
    private Long runId;

    /** MATCHED, UNMATCHED_A, UNMATCHED_B, DISCREPANCY */
    @TableField("match_status")
    private String matchStatus;

    @TableField("source_a_record_id")
    private Long sourceARecordId;

    @TableField("source_a_ref")
    private String sourceARef;

    @TableField("source_a_amount")
    private BigDecimal sourceAAmount;

    @TableField("source_a_date")
    private LocalDate sourceADate;

    @TableField("source_b_record_id")
    private Long sourceBRecordId;

    @TableField("source_b_ref")
    private String sourceBRef;

    @TableField("source_b_amount")
    private BigDecimal sourceBAmount;

    @TableField("source_b_date")
    private LocalDate sourceBDate;

    @TableField("amount_difference")
    private BigDecimal amountDifference;

    /** Date difference in days */
    @TableField("date_difference")
    private Integer dateDifference;

    /** 0-100 confidence score */
    @TableField("match_score")
    private BigDecimal matchScore;

    /** null, APPROVED, ADJUSTED, WRITTEN_OFF */
    @TableField("resolution")
    private String resolution;

    @TableField("resolution_notes")
    private String resolutionNotes;

    @TableField("resolved_by")
    private Long resolvedBy;

    @TableField("resolved_at")
    private Instant resolvedAt;
}
