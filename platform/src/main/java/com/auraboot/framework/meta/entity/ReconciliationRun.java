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
 * Reconciliation run entity.
 * Immutable execution record with summary statistics.
 * Status: PENDING → RUNNING → COMPLETED | FAILED
 */
@Data
@TableName("ab_reconciliation_run")
public class ReconciliationRun {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("run_code")
    private String runCode;

    @TableField("profile_id")
    private Long profileId;

    /** PENDING, RUNNING, COMPLETED, FAILED */
    @TableField("status")
    private String status;

    @TableField("period_start")
    private LocalDate periodStart;

    @TableField("period_end")
    private LocalDate periodEnd;

    @TableField("total_source_a")
    private Integer totalSourceA;

    @TableField("total_source_b")
    private Integer totalSourceB;

    @TableField("matched_count")
    private Integer matchedCount;

    @TableField("unmatched_a_count")
    private Integer unmatchedACount;

    @TableField("unmatched_b_count")
    private Integer unmatchedBCount;

    @TableField("discrepancy_count")
    private Integer discrepancyCount;

    @TableField("matched_amount")
    private BigDecimal matchedAmount;

    @TableField("unmatched_a_amount")
    private BigDecimal unmatchedAAmount;

    @TableField("unmatched_b_amount")
    private BigDecimal unmatchedBAmount;

    @TableField("error_message")
    private String errorMessage;

    @TableField("started_at")
    private Instant startedAt;

    @TableField("completed_at")
    private Instant completedAt;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private Instant createdAt;

    /**
     * Check if the run has reached a terminal state.
     */
    public boolean isTerminal() {
        return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status);
    }
}
