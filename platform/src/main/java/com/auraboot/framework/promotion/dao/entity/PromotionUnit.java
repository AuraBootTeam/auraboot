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
 * One resource included in a {@link Promotion} plan. PoC scope: only resourceType=PAGE_SCHEMA.
 */
@Data
@TableName(value = "ab_promotion_unit", autoResultMap = true)
public class PromotionUnit {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;
    private Long tenantId;

    private Long promotionId;

    /** Resource kind (PAGE_SCHEMA in PoC). DB CHECK constraint enforces enum values. */
    private String resourceType;

    /** Source PageSchema.pid the unit refers to. */
    private String resourcePid;

    /** Version of the source resource captured when promotion was drafted. */
    private Integer sourceVersion;

    /** Version assigned in target env when applied. NULL until APPLIED. */
    private Integer targetVersion;

    /** Target PageSchema.pid captured by the latest drift assessment. */
    private String targetResourcePid;

    private String driftStatus;
    private String driftFingerprint;
    private String driftDecision;
    private String driftDecisionPid;
    private String driftExecutionStatus;
    private String driftExecutionPid;
    @TableField(value = "drift_execution_payload", typeHandler = JsonbStringTypeHandler.class,
            jdbcType = JdbcType.OTHER)
    private String driftExecutionPayload;

    private Integer sortOrder;

    private Date createdAt;

    @TableField("deleted_flag")
    private Boolean deletedFlag;
}
