package com.auraboot.framework.bpm.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.auraboot.framework.bpm.typehandler.JsonListLongTypeHandler;
import com.auraboot.framework.bpm.typehandler.JsonListMapTypeHandler;
import com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * @deprecated Since unified inbox (ab_inbox_item) replaces approval-specific persistence.
 * Approval data now stored in: mt_* (business data via Command pipeline),
 * SmartEngine process variables (decision/comment/signature),
 * ab_inbox_item (inbox routing and card payload).
 * Multi-approval uses SmartEngine multiInstance (BPMN standard).
 * Kept for backward compatibility with Command Chain Approval mode
 * (currently unused by any business plugin).
 *
 * @see com.auraboot.framework.inbox.model.InboxItem
 */
@Deprecated
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_approval_task", autoResultMap = true)
public class ApprovalTask {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("chain_execution_id")
    private String chainExecutionId;

    @TableField("chain_node_id")
    private String chainNodeId;

    @TableField("process_key")
    private String processKey;

    @TableField("business_key")
    private String businessKey;

    @TableField("task_title")
    private String taskTitle;

    @TableField("task_description")
    private String taskDescription;

    @TableField("priority")
    private String priority;

    @TableField("status")
    private String status;

    @TableField("assignee_strategy")
    private String assigneeStrategy;

    @TableField(value = "assignee_user_ids", typeHandler = JsonListLongTypeHandler.class)
    private List<Long> assigneeUserIds;

    @TableField("assignee_rule_type")
    private String assigneeRuleType;

    @TableField(value = "assignee_rule_config", typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> assigneeRuleConfig;

    @TableField("actual_approver_id")
    private Long actualApproverId;

    @TableField("form_ref")
    private String formRef;

    @TableField(value = "form_snapshot", typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> formSnapshot;

    @TableField(value = "approval_data", typeHandler = PluginSettingsTypeHandler.class)
    private Map<String, Object> approvalData;

    @TableField("approval_comment")
    private String approvalComment;

    /** Base64-encoded PNG signature image */
    @TableField("signature")
    private String signature;

    /** JSON array of attachment file references [{fileId, fileName, fileSize, url}] */
    @TableField(value = "attachments", typeHandler = JsonListMapTypeHandler.class)
    private List<Map<String, Object>> attachments;

    @TableField("deadline_at")
    private Instant deadlineAt;

    @TableField("completed_at")
    private Instant completedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableField("created_by")
    private Long createdBy;

    @TableField("updated_by")
    private Long updatedBy;
}
