package com.auraboot.framework.semantic.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * A pre-aggregation over a published semantic model (BI rectification R3).
 * Backed by one PostgreSQL materialized view compiled from the governed
 * semantic pipeline; refresh and staleness are managed by the engine.
 */
@Data
@TableName(value = "ab_semantic_preagg", autoResultMap = true)
public class AbSemanticPreagg {

    @TableId
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    private String name;

    @TableField("semantic_model_pid")
    private String semanticModelPid;

    @TableField("metric_code")
    private String metricCode;

    /** JSON array of dimension codes, e.g. ["alias_language"]. */
    @TableField(value = "dimension_codes", jdbcType = org.apache.ibatis.type.JdbcType.OTHER,
            typeHandler = com.auraboot.framework.tenant.typehandler.JsonStringTypeHandler.class)
    private String dimensionCodes;

    @TableField("refresh_minutes")
    private Integer refreshMinutes;

    @TableField("mv_name")
    private String mvName;

    @TableField("last_refreshed_at")
    private OffsetDateTime lastRefreshedAt;

    @TableField("last_refresh_rows")
    private Long lastRefreshRows;

    @TableField("created_by")
    private Long createdBy;

    @TableField("created_at")
    private OffsetDateTime createdAt;

    @TableField("updated_at")
    private OffsetDateTime updatedAt;

    @TableField("deleted_flag")
    private Boolean deletedFlag;
}
