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
 * Audit row for an explicit blast-radius acknowledgement.
 */
@Data
@TableName(value = "ab_drt_impact_ack", autoResultMap = true)
public class DecisionImpactAckEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("action_type")
    private String actionType;

    @TableField("target_type")
    private String targetType;

    @TableField("target_code")
    private String targetCode;

    @TableField("target_pid")
    private String targetPid;

    @TableField("target_path")
    private String targetPath;

    @TableField("impact_summary")
    private String impactSummary;

    @TableField(value = "impact_snapshot_json", typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode impactSnapshotJson;

    @TableField("acknowledged_by")
    private String acknowledgedBy;

    @TableField("acknowledged_at")
    private Instant acknowledgedAt;

    @TableField("note")
    private String note;

    @TableField("created_at")
    private Instant createdAt;
}
