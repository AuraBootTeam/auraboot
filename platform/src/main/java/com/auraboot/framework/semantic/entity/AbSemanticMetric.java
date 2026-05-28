package com.auraboot.framework.semantic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Semantic metric — 5 type variants (simple / ratio / cumulative / derived / conversion).
 *
 * <p>Backed by table {@code ab_semantic_metric}. PRD 16 §3.3.
 *
 * <p>{@code type_params} JSON shape varies by {@code metric_type}; validated by
 * SemanticValidator. Compilation handled by MetricCompiler.
 */
@Data
@TableName("ab_semantic_metric")
public class AbSemanticMetric {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String pid;

    private Long tenantId;

    private String semanticModelPid;

    /** Metric code, unique within (tenant, version). */
    private String code;

    /** {@link com.auraboot.framework.semantic.enums.MetricType} name (lowercase YAML). */
    private String metricType;

    /** JSON, shape depends on metricType. See PRD 16 §3.3. */
    private String typeParams;

    /** Optional SQL WHERE fragment using declared dimensions/measures only. */
    private String filterExpr;

    private String labelI18n;

    private String description;

    /** JSON array of permission codes, e.g. {@code ["sales.read"]}. */
    private String requiredPermissions;

    /** {@link com.auraboot.framework.semantic.enums.SemanticModelStatus} name. */
    private String status;

    private String version;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    private Long createdBy;
    private Long updatedBy;

    @TableLogic
    private Boolean deletedFlag;
}
