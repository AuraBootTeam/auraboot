package com.auraboot.framework.bpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.auraboot.framework.bpm.typehandler.JsonListMapTypeHandler;
import lombok.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Entity for SLA (Service Level Agreement) configuration.
 * Defines deadline rules, warning thresholds, and target bindings for BPM processes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_sla_config", autoResultMap = true)
public class SlaConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Unique identifier (ULID).
     */
    private String pid;

    /**
     * Tenant ID for multi-tenancy isolation.
     */
    private Long tenantId;

    /**
     * SLA configuration name.
     */
    private String name;

    /**
     * Target type: PROCESS | NODE | TASK.
     */
    private String targetType;

    /**
     * Target key (process key, node id, etc.).
     */
    private String targetKey;

    /**
     * Associated domain code.
     */
    private String domainCode;

    /**
     * Deadline calculation mode: FIXED | EXPRESSION | FIELD.
     */
    private String deadlineMode;

    /**
     * Deadline value (duration string, expression, or field reference).
     */
    private String deadlineValue;

    /**
     * Whether to use business calendar for deadline calculation.
     */
    private Boolean businessCalendar;

    /**
     * Warning rules configuration (JSONB).
     */
    @TableField(typeHandler = JsonListMapTypeHandler.class)
    private List<Map<String, Object>> warningRules;

    /**
     * Associated model code.
     */
    private String modelCode;

    /**
     * Field code for deadline source (when deadlineMode=FIELD).
     */
    private String deadlineField;

    /**
     * Field code for priority source.
     */
    private String priorityField;

    /**
     * Suspend policy: PAUSE (pause SLA timer) | CONTINUE (keep running) | CANCEL (cancel SLA).
     */
    @Builder.Default
    private String suspendPolicy = "pause";

    /**
     * Whether this SLA config is enabled.
     */
    @Builder.Default
    private Boolean enabled = true;

    /**
     * Record creation time.
     */
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    /**
     * Record update time.
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    /**
     * User who created the record.
     */
    private Long createdBy;

    /**
     * User who last updated the record.
     */
    private Long updatedBy;

    /**
     * Soft delete flag.
     */
    @Builder.Default
    @TableLogic
    private Boolean deletedFlag = false;
}
