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
 * Progressive rollout policy for a decision version pair.
 */
@Data
@TableName(value = "ab_drt_rollout_policy", autoResultMap = true)
public class DecisionRolloutPolicyEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("decision_code")
    private String decisionCode;

    @TableField("baseline_version")
    private Integer baselineVersion;

    @TableField("candidate_version")
    private Integer candidateVersion;

    @TableField("status")
    private String status;

    @TableField("percentage")
    private Integer percentage;

    @TableField(value = "cohort_json", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode cohortJson;

    @TableField(value = "segment_json", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode segmentJson;

    @TableField("routing_key_expr")
    private String routingKeyExpr;

    @TableField("salt")
    private String salt;

    @TableField("started_by")
    private String startedBy;

    @TableField("started_at")
    private Instant startedAt;

    @TableField("ended_by")
    private String endedBy;

    @TableField("ended_at")
    private Instant endedAt;

    @TableField(value = "audit_json", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode auditJson;

    @TableField("created_at")
    private Instant createdAt;

    @TableField("updated_at")
    private Instant updatedAt;
}
