package com.auraboot.framework.semantic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import com.auraboot.framework.tenant.typehandler.JsonStringTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Downstream consumer of semantic metrics — dashboard / notebook / ML model / app.
 *
 * <p>Dbt-style exposure: explicit declaration of "who consumes this metric"
 * so impact analysis on metric changes is possible.
 *
 * <p>Backed by table {@code ab_semantic_exposure}. PRD 16 §5.1.
 */
@Data
@TableName("ab_semantic_exposure")
public class AbSemanticExposure {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String pid;

    private Long tenantId;

    private String pluginCode;

    /** Exposure code, unique within (tenant). */
    private String code;

    /** dashboard / notebook / analysis / ml / application */
    private String exposureType;

    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonStringTypeHandler.class)
    private String labelI18n;

    private String description;

    private Long ownerUserId;

    private String ownerEmail;

    private String url;

    /** high / medium / low */
    private String maturity;

    /** JSON array, e.g. {@code [{"type":"metric","pid":"01HXY..."},{"type":"model","pid":"01ABC..."}]}. */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonStringTypeHandler.class)
    private String dependsOn;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableLogic
    private Boolean deletedFlag;
}
