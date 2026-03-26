package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * Decision Alarm entity.
 * Monitoring alerts for evidence/decision/invariant issues.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
@TableName("ab_decision_alarm")
public class DecisionAlarm {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    /**
     * Alarm type: EVIDENCE_MISSING / DECISION_MISSING / INVARIANT_VIOLATION.
     */
    @TableField("alarm_type")
    private String alarmType;

    @TableField("subject_type")
    private String subjectType;

    @TableField("subject_id")
    private String subjectId;

    @TableField("stage")
    private String stage;

    /**
     * Severity: WARN / ERROR / CRITICAL.
     */
    @TableField("severity")
    private String severity;

    @TableField("message")
    private String message;

    /**
     * Status: OPEN / ACKNOWLEDGED / RESOLVED.
     */
    @TableField("status")
    private String status;

    @TableField("acknowledged_at")
    private Instant acknowledgedAt;

    @TableField("resolved_at")
    private Instant resolvedAt;

    @TableField("created_at")
    private Instant createdAt;
}
