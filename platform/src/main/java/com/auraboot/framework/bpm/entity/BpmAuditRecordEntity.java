package com.auraboot.framework.bpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * BPM audit record entity.
 * Maps to table: ab_bpm_audit_record
 *
 * @author AuraBoot Team
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_bpm_audit_record", autoResultMap = true)
public class BpmAuditRecordEntity {

    /**
     * Primary key (auto-increment)
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * Unique business identifier (ULID)
     */
    @TableField("pid")
    private String pid;

    /**
     * Tenant ID
     */
    @TableField("tenant_id")
    private Long tenantId;

    /**
     * User ID who performed the operation
     */
    @TableField("user_id")
    private String userId;

    /**
     * Operation type (e.g. PROCESS_START, TASK_COMPLETE, TASK_DELEGATE)
     */
    @TableField("operation")
    private String operation;

    /**
     * Process instance ID
     */
    @TableField("process_instance_id")
    private String processInstanceId;

    /**
     * Task ID
     */
    @TableField("task_id")
    private String taskId;

    /**
     * Process definition key
     */
    @TableField("process_definition_key")
    private String processDefinitionKey;

    /**
     * Process definition version
     */
    @TableField("version")
    private Integer version;

    /**
     * Operation details (stored as JSONB)
     */
    @TableField(value = "details", typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> details;

    /**
     * Client IP address
     */
    @TableField("ip_address")
    private String ipAddress;

    /**
     * Operation result: SUCCESS or FAILURE
     */
    @TableField("result")
    private String result;

    /**
     * Error message when result is FAILURE
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * Record creation timestamp
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
