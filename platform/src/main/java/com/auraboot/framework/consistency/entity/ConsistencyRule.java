package com.auraboot.framework.consistency.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Consistency rule entity for cross-document validation.
 * E.g. "shipment qty must not exceed order qty"
 */
@Data
@TableName(value = "ab_consistency_rule")
public class ConsistencyRule {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("code")
    private String code;

    @TableField("name")
    private String name;

    @TableField("rule_type")
    private String ruleType;

    @TableField("severity")
    private String severity;

    @TableField("source_model")
    private String sourceModel;

    @TableField("source_field")
    private String sourceField;

    @TableField("target_model")
    private String targetModel;

    @TableField("target_field")
    private String targetField;

    @TableField("link_field")
    private String linkField;

    @TableField("aggregation")
    private String aggregation;

    @TableField("operator")
    private String operator;

    @TableField("message_template")
    private String messageTemplate;

    @TableField("enabled")
    private Boolean enabled;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("created_at")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("deleted_flag")
    private Boolean deletedFlag;

    /**
     * Supported aggregation functions
     */
    public enum AggregationType {
        SUM, COUNT, MAX, MIN, AVG
    }

    /**
     * Supported comparison operators
     */
    public enum ComparisonOperator {
        LE, LT, EQ, GE, GT, NE
    }

    /**
     * Rule types
     */
    public enum RuleType {
        CROSS_DOCUMENT
    }

    /**
     * Severity levels
     */
    public enum Severity {
        ERROR, WARNING, INFO
    }
}
