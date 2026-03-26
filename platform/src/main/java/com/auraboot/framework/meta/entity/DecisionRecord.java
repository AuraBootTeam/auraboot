package com.auraboot.framework.meta.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.apache.ibatis.type.JdbcType;

import java.time.Instant;

/**
 * Decision Record entity.
 * Represents a formal decision made about a subject at a specific stage.
 * Idempotent: UNIQUE(tenant_id, subject_type, subject_id, stage).
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
@TableName("ab_decision_record")
public class DecisionRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("subject_type")
    private String subjectType;

    @TableField("subject_id")
    private String subjectId;

    @TableField("stage")
    private String stage;

    @TableField("outcome")
    private String outcome;

    @TableField(value = "evidence_summary", jdbcType = JdbcType.OTHER,
            typeHandler = JsonbStringTypeHandler.class)
    private String evidenceSummary;

    @TableField(value = "invariant_results", jdbcType = JdbcType.OTHER,
            typeHandler = JsonbStringTypeHandler.class)
    private String invariantResults;

    @TableField(value = "trace", jdbcType = JdbcType.OTHER,
            typeHandler = JsonbStringTypeHandler.class)
    private String trace;

    @TableField("decided_by")
    private Long decidedBy;

    @TableField("decided_at")
    private Instant decidedAt;

    @TableField("created_at")
    private Instant createdAt;
}
