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
 * Decision Runtime evaluation audit log — immutable once written.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@TableName(value = "ab_drt_log", autoResultMap = true)
public class DrtLogEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    /** Caller-supplied or auto-generated evaluation trace id */
    @TableField("trace_id")
    private String traceId;

    /** Parent workflow / automation run id */
    @TableField("correlation_id")
    private String correlationId;

    @TableField("decision_code")
    private String decisionCode;

    @TableField("decision_version")
    private Integer decisionVersion;

    @TableField("selected_version")
    private Integer selectedVersion;

    @TableField("rollout_policy_pid")
    private String rolloutPolicyPid;

    @TableField("rollout_bucket")
    private Integer rolloutBucket;

    @TableField("rollout_arm")
    private String rolloutArm;

    @TableField("routing_key")
    private String routingKey;

    @TableField("rollout_result_key")
    private String rolloutResultKey;

    @TableField("kind")
    private String kind;

    @TableField("runtime_adapter")
    private String runtimeAdapter;

    /** AUTOMATION | BPM | API | TEST … */
    @TableField("caller_type")
    private String callerType;

    /** PID of the calling entity */
    @TableField("caller_ref")
    private String callerRef;

    /** SHA-256 of the serialised input context */
    @TableField("input_digest")
    private String inputDigest;

    /** SHA-256 of the serialised DecisionResult */
    @TableField("result_digest")
    private String resultDigest;

    @TableField("matched")
    private Boolean matched;

    /** MATCHED | NOT_MATCHED | ERROR | SKIPPED | UNKNOWN */
    @TableField("status")
    private String status;

    /** Serialised DecisionResult.matchedRules */
    @TableField(value = "matched_rules_json",
                typeHandler = JsonNodeTypeHandler.class,
                jdbcType = JdbcType.OTHER)
    private JsonNode matchedRulesJson;

    /** Serialised DecisionResult.outputs for Trace UI readability */
    @TableField(value = "output_snapshot",
                typeHandler = JsonNodeTypeHandler.class,
                jdbcType = JdbcType.OTHER)
    private JsonNode outputSnapshot;

    /** Trace diagnostics for UI readability without storing the full input context */
    @TableField(value = "trace_snapshot",
                typeHandler = JsonNodeTypeHandler.class,
                jdbcType = JdbcType.OTHER)
    private JsonNode traceSnapshot;

    @TableField("duration_ms")
    private Long durationMs;

    @TableField("error_code")
    private String errorCode;

    @TableField("error_message")
    private String errorMessage;

    @TableField("created_at")
    private Instant createdAt;
}
