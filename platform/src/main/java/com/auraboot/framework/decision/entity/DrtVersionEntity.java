package com.auraboot.framework.decision.entity;

import com.auraboot.framework.decision.typehandler.JsonNodeTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Decision Runtime version row — versioned, immutable-once-published payload.
 * JSONB columns use {@link JsonNodeTypeHandler} following the automation module pattern.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@TableName(value = "ab_drt_version", autoResultMap = true)
public class DrtVersionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("decision_code")
    private String decisionCode;

    /** Monotonically incrementing per (tenant, decision_code) */
    @TableField("version")
    private Integer version;

    /** Optional human label, e.g. "v1.2-hotfix" */
    @TableField("version_tag")
    private String versionTag;

    /**
     * Lifecycle: DRAFT → VALIDATED → PUBLISHED → DEPRECATED → RETIRED.
     * Stored as the enum name string.
     */
    @TableField("status")
    private String status;

    /** Decision kind: SIMPLE_CONDITION | DECISION_TABLE | RULE_SET | EXPRESSION | COMPOSITE */
    @TableField("kind")
    private String kind;

    /** Runtime adapter: AST_EVALUATOR | DMN | … */
    @TableField("runtime_adapter")
    private String runtimeAdapter;

    @TableField("content_format")
    private String contentFormat;

    /** The serialised decision payload */
    @TableField(value = "content_json",
                typeHandler = JsonNodeTypeHandler.class,
                jdbcType = JdbcType.OTHER)
    private JsonNode contentJson;

    @TableField(value = "input_schema_json",
                typeHandler = JsonNodeTypeHandler.class,
                jdbcType = JdbcType.OTHER)
    private JsonNode inputSchemaJson;

    @TableField(value = "output_schema_json",
                typeHandler = JsonNodeTypeHandler.class,
                jdbcType = JdbcType.OTHER)
    private JsonNode outputSchemaJson;

    @TableField(value = "context_schema_json",
                typeHandler = JsonNodeTypeHandler.class,
                jdbcType = JdbcType.OTHER)
    private JsonNode contextSchemaJson;

    /** Field references extracted by {@code DecisionRuntime.validate()} */
    @TableField(value = "field_refs_json",
                typeHandler = JsonNodeTypeHandler.class,
                jdbcType = JdbcType.OTHER)
    private JsonNode fieldRefsJson;

    /** Function references extracted by {@code DecisionRuntime.validate()} */
    @TableField(value = "function_refs_json",
                typeHandler = JsonNodeTypeHandler.class,
                jdbcType = JdbcType.OTHER)
    private JsonNode functionRefsJson;

    /** SHA-256 of content_json */
    @TableField("content_hash")
    private String contentHash;

    @TableField("effective_from")
    private Instant effectiveFrom;

    @TableField("effective_to")
    private Instant effectiveTo;

    @TableField("published_by")
    private String publishedBy;

    @TableField("published_at")
    private Instant publishedAt;

    @TableField("approval_by")
    private String approvalBy;

    @TableField("approval_at")
    private Instant approvalAt;

    @TableField("approval_note")
    private String approvalNote;

    @TableField("created_at")
    private Instant createdAt;
}
