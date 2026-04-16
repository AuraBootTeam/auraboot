package com.auraboot.framework.plugin.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Entity for BPM process definition with form bindings.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_bpm_process_definition", autoResultMap = true)
public class BpmProcessDefinition {

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
     * Reference to plugin that created this process.
     */
    private String pluginPid;

    /**
     * Unique process key within tenant.
     */
    private String processKey;

    /**
     * Process display name.
     */
    private String processName;

    /**
     * Process description.
     */
    private String description;

    /**
     * Process category for organization.
     */
    private String category;

    /**
     * BPMN 2.0 XML content.
     */
    private String bpmnContent;

    /**
     * Form bindings for user tasks.
     * Key: user task ID, Value: form configuration.
     */
    @TableField(typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class)
    private Map<String, Object> formBindings;

    /**
     * Business data bindings for process variables.
     */
    @TableField(typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class)
    private Map<String, Object> businessDataBindings;

    /**
     * Status: DRAFT, DEPLOYED, SUSPENDED, ARCHIVED.
     */
    @Builder.Default
    private String status = "draft";

    /**
     * SmartEngine deployment ID.
     */
    private String deploymentId;

    /**
     * Deployment timestamp.
     */
    private Instant deployedAt;

    /**
     * Version number.
     */
    @Builder.Default
    private Integer version = 1;

    /**
     * Whether this is the current version.
     */
    @Builder.Default
    private Boolean isCurrent = true;

    /**
     * Extension properties.
     */
    @TableField(typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class)
    private Map<String, Object> extension;

    /**
     * Withdraw policy: strict | loose | none. Default strict.
     */
    @TableField(value = "withdraw_policy")
    private String withdrawPolicy;

    /**
     * CC policy: initiator | assignee | all. Default all.
     */
    @TableField(value = "cc_policy")
    private String ccPolicy;

    /**
     * Required permissions override, keyed by operation name (withdraw/cc/...).
     */
    @TableField(value = "required_permissions",
            typeHandler = com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler.class)
    private Map<String, Object> requiredPermissions;

    /**
     * Hours before a pending approval task is considered overdue.
     * NULL means no timeout configured.
     */
    @TableField("timeout_hours")
    private Integer timeoutHours;

    /**
     * Action to take when a task exceeds timeout_hours.
     * Values: ESCALATE (notify escalate_to_user_id), AUTO_APPROVE, AUTO_REJECT.
     */
    @TableField("timeout_action")
    private String timeoutAction;

    /**
     * User ID to escalate to when timeout_action=ESCALATE.
     */
    @TableField("escalate_to_user_id")
    private Long escalateToUserId;

    /**
     * Soft delete flag.
     */
    @Builder.Default
    @TableLogic
    private Boolean deletedFlag = false;

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
     * Check if the process is deployed.
     */
    public boolean isDeployed() {
        return StatusConstants.DEPLOYED.equals(status) && deploymentId != null;
    }

    /**
     * Check if the process is a draft.
     */
    public boolean isDraft() {
        return StatusConstants.DRAFT.equals(status);
    }

    /**
     * Check if the process is suspended.
     */
    public boolean isSuspended() {
        return StatusConstants.SUSPENDED.equals(status);
    }
}
