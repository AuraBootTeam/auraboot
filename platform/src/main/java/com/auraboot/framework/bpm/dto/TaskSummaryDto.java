package com.auraboot.framework.bpm.dto;

import com.auraboot.smart.framework.engine.model.instance.TaskAssigneeInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * Task summary DTO that wraps a {@link TaskInstance} and adds enriched fields
 * such as {@code businessKey} (sourced from the corresponding ProcessInstance's
 * {@code bizUniqueId}) that are not present on the SmartEngine native interface.
 *
 * <p>The frontend {@code mapTaskInstance} function maps {@code r.businessKey} directly
 * from the JSON — this DTO ensures the field is present in the serialized response.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskSummaryDto {

    // --- Fields mirrored from TaskInstance ---
    private String instanceId;
    /** Alias for instanceId — the canonical task ID used by task operations. */
    private String taskId;
    private String taskName;
    private String title;
    private String processDefinitionIdAndVersion;
    private String processDefinitionActivityId;
    private String processDefinitionType;
    private String processInstanceId;
    private String activityInstanceId;
    private String executionInstanceId;
    private String tenantId;
    private String claimUserId;
    private String tag;
    private String status;
    private String comment;
    private String extension;
    private String domainCode;
    private String extra;
    private Integer priority;
    private Date startTime;
    private Date completeTime;
    private Date claimTime;
    private List<TaskAssigneeInstance> taskAssigneeInstanceList;

    // --- Enriched field not on TaskInstance ---
    /**
     * Business key (= ProcessInstance.bizUniqueId).
     * Populated by the controller from the parent process instance.
     */
    private String businessKey;

    /**
     * Build a TaskSummaryDto from a raw TaskInstance.
     * {@code businessKey} is left null; caller must set it separately via {@link #setBusinessKey}.
     */
    public static TaskSummaryDto from(TaskInstance t) {
        TaskSummaryDto dto = new TaskSummaryDto();
        if (t == null) return dto;

        dto.instanceId = t.getInstanceId();
        dto.taskId = t.getInstanceId();
        dto.title = t.getTitle();
        dto.taskName = t.getTitle();
        dto.processDefinitionIdAndVersion = t.getProcessDefinitionIdAndVersion();
        dto.processDefinitionActivityId = t.getProcessDefinitionActivityId();
        dto.processDefinitionType = t.getProcessDefinitionType();
        dto.processInstanceId = t.getProcessInstanceId();
        dto.activityInstanceId = t.getActivityInstanceId();
        dto.executionInstanceId = t.getExecutionInstanceId();
        dto.tenantId = t.getTenantId();
        dto.claimUserId = t.getClaimUserId();
        dto.tag = t.getTag();
        dto.status = t.getStatus();
        dto.comment = t.getComment();
        dto.extension = t.getExtension();
        dto.domainCode = t.getDomainCode();
        dto.extra = t.getExtra();
        dto.priority = t.getPriority();
        dto.startTime = t.getStartTime();
        dto.completeTime = t.getCompleteTime();
        dto.claimTime = t.getClaimTime();
        dto.taskAssigneeInstanceList = t.getTaskAssigneeInstanceList();
        return dto;
    }
}
