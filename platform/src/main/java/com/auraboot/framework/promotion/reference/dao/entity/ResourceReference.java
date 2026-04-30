package com.auraboot.framework.promotion.reference.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;
import java.util.Objects;

/**
 * Reverse-index entry for "page X references model/field Y". Used by the Diff Viewer impact
 * sidebar (UX contract real-use criterion #1). Tenant + env scoped — same page in different
 * envs can reference different things if its content diverged.
 */
@Data
@TableName(value = "ab_resource_reference", autoResultMap = true)
public class ResourceReference {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;
    private Long tenantId;
    private Long envId;

    private String sourceType;
    private String sourceId;

    private String targetType;
    private String targetCode;

    private String refType;

    private Date createdAt;

    @TableField("deleted_flag")
    private Boolean deletedFlag;

    /** Equality used by extractor's de-dup Set. Excludes id/pid/createdAt/deletedFlag. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ResourceReference that)) return false;
        return Objects.equals(tenantId, that.tenantId)
                && Objects.equals(envId, that.envId)
                && Objects.equals(sourceType, that.sourceType)
                && Objects.equals(sourceId, that.sourceId)
                && Objects.equals(targetType, that.targetType)
                && Objects.equals(targetCode, that.targetCode)
                && Objects.equals(refType, that.refType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, envId, sourceType, sourceId, targetType, targetCode, refType);
    }
}
