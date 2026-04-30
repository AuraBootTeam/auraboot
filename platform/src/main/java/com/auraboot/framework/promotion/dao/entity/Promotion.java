package com.auraboot.framework.promotion.dao.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.util.Date;

/**
 * Promotion plan entity. NOT @EnvScoped — promotions span environments by definition (have
 * source_env_id and target_env_id columns). Tenant-scoped via the standard interceptor.
 *
 * <p>Status is stored as String for forward compatibility; service layer maps to/from
 * {@link com.auraboot.framework.promotion.domain.PromotionStatus} via valueOf/name().
 */
@Data
@TableName(value = "ab_promotion", autoResultMap = true)
public class Promotion {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;
    private Long tenantId;

    private Long sourceEnvId;
    private Long targetEnvId;

    /** PromotionStatus name; CHECK constraint enforces enum values at DB level. */
    private String status;

    /** JSONB: {unitCount, resourceTypes, ...} aggregate for list views. */
    @TableField(value = "plan_summary", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String planSummary;

    /** JSONB: last DryRunResult {conflicts, missingDeps, validatedAt}. */
    @TableField(value = "dry_run_result", typeHandler = JsonbStringTypeHandler.class, jdbcType = JdbcType.OTHER)
    private String dryRunResult;

    private Date dryRunAt;

    private String failureReason;

    // Terminal-state audit columns. Service must write these atomically with the status flip.
    private Date appliedAt;
    private Long appliedBy;
    private String appliedReason;

    private Date rejectedAt;
    private Long rejectedBy;
    private String rejectedReason;

    private Date createdAt;
    private Long createdBy;
    private Date updatedAt;
    private Long updatedBy;

    @TableField("deleted_flag")
    private Boolean deletedFlag;
}
