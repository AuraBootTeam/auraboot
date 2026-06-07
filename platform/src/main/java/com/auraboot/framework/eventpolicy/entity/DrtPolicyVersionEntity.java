package com.auraboot.framework.eventpolicy.entity;

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
 * Event Policy version row — versioned, immutable-once-published rules payload.
 * JSONB column {@code rules_json} uses {@link JsonNodeTypeHandler} following the decision module pattern.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@TableName(value = "ab_drt_policy_version", autoResultMap = true)
public class DrtPolicyVersionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("policy_code")
    private String policyCode;

    /** Monotonically incrementing per (tenant, policy_code) */
    @TableField("version")
    private Integer version;

    /**
     * Lifecycle: DRAFT → VALIDATED → PUBLISHED → DEPRECATED → RETIRED.
     * Stored as the enum name string (com.auraboot.framework.decision.model.VersionStatus).
     */
    @TableField("status")
    private String status;

    /** Execution phase: BEFORE_SUBMIT | AFTER_COMMIT | ASYNC_WORKER */
    @TableField("phase")
    private String phase;

    /** Match mode: FIRST_MATCH | COLLECT_ALL | UNIQUE | PRIORITY_FIRST */
    @TableField("match_mode")
    private String matchMode;

    /** Execution mode: ORDERED | UNORDERED */
    @TableField("execution_mode")
    private String executionMode;

    /** Failure strategy: FAIL_FAST | CONTINUE_ON_ERROR | ALL_OR_NOTHING | RETRY_ASYNC | DEAD_LETTER */
    @TableField("failure_strategy")
    private String failureStrategy;

    /** Conflict strategy: REJECT_ON_CONFLICT | PRIORITY_WINS | LAST_WRITE_WINS | MERGE_IF_COMPATIBLE */
    @TableField("conflict_strategy")
    private String conflictStrategy;

    /** Dedup strategy: NONE | BY_IDEMPOTENCY_KEY | BY_ACTION_TYPE_AND_TARGET */
    @TableField("dedup_strategy")
    private String dedupStrategy;

    /** Serialised List<PolicyRule> — each rule has ConditionNode + List<PolicyAction> */
    @TableField(value = "rules_json",
                typeHandler = JsonNodeTypeHandler.class,
                jdbcType = JdbcType.OTHER)
    private JsonNode rulesJson;

    /** SHA-256 of rules_json */
    @TableField("content_hash")
    private String contentHash;

    @TableField("published_by")
    private String publishedBy;

    @TableField("published_at")
    private Instant publishedAt;

    @TableField("created_at")
    private Instant createdAt;
}
