package com.auraboot.framework.semantic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

/**
 * Semantic dimension — a column exposed for grouping/filtering in queries.
 *
 * <p>Backed by table {@code ab_semantic_dimension}. PRD 16 §3.1.
 */
@Data
@TableName("ab_semantic_dimension")
public class AbSemanticDimension {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String pid;

    private Long tenantId;

    /** Parent semantic model pid. */
    private String semanticModelPid;

    /** Dimension code, unique within (tenant, model). */
    private String code;

    /** MetaField.code reference within the model. */
    private String fieldRef;

    /** {@link com.auraboot.framework.semantic.enums.DimensionType} name (lowercase serialized). */
    private String dimType;

    private String labelI18n;

    private String description;

    /** JSON array, e.g. {@code ["day","week","month","quarter","year"]}. Required when dimType = TIME. */
    private String timeGrains;

    /** Exactly one dimension per model may set true. */
    private Boolean primaryTime;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableLogic
    private Boolean deletedFlag;
}
