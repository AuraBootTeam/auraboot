package com.auraboot.framework.promotion.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

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

    private Integer sortOrder;

    private Date createdAt;

    @TableField("deleted_flag")
    private Boolean deletedFlag;
}
