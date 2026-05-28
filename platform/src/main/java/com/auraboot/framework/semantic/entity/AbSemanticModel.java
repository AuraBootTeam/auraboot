package com.auraboot.framework.semantic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import com.auraboot.framework.tenant.typehandler.JsonStringTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Semantic model — a {@code *.semantic.yml} file's top-level container.
 *
 * <p>Backed by table {@code ab_semantic_model}.
 * See migration {@code 2026-05-28-semantic-layer-v01.sql} and PRD 16 §5.
 */
@Data
@TableName("ab_semantic_model")
public class AbSemanticModel {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** ULID, 32 chars. Stable identifier across versions. */
    private String pid;

    private Long tenantId;

    /** Owning plugin code (e.g. "sales"). */
    private String pluginCode;

    /** {@code semantic_model.code} (e.g. "sales"). Unique within (tenant, plugin, version). */
    private String code;

    /** {@code semantic_model.model_ref} — references MetaModel.code. */
    private String modelRef;

    /** {@code semantic_model.primary_entity}. */
    private String primaryEntity;

    /** i18n map, e.g. {@code {"zh-CN":"销售","en-US":"Sales"}}. Stored as JSONB. */
    @TableField(jdbcType = JdbcType.OTHER, typeHandler = JsonStringTypeHandler.class)
    private String labelI18n;

    private String description;

    /** semver. v0.1 always "0.1". */
    private String version;

    /** {@link com.auraboot.framework.semantic.enums.SemanticModelStatus} name. */
    private String status;

    /** Raw YAML source for audit + diff. */
    private String yamlSource;

    /** SHA-256 of yamlSource, used for change detection. */
    private String yamlSha;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    private Long createdBy;
    private Long updatedBy;

    @TableLogic
    private Boolean deletedFlag;
}
