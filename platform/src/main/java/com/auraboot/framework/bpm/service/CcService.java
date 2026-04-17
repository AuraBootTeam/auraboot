package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditOperation;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.extension.BpmExtensionAccessor;
import com.auraboot.framework.bpm.model.CcPolicy;
import com.auraboot.framework.bpm.util.BpmSecurityUtil;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.constant.NotificationConstant;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Sends CC notifications for a BPM task.
 *
 * <p>Authorization is governed by the process-level CcPolicy declared in BPMN
 * &lt;smart:properties&gt; under aura.ccPolicy (initiator | assignee | all),
 * with an optional per-activity override under aura.ccPolicyOverride.
 *
 * <p>Storage and per-receiver fan-out is delegated to SmartEngine's
 * NotificationCommandService (table {@code se_notification_instance},
 * notification_type={@code cc}). AuraBoot only writes a business-semantic
 * audit record ("I executed cc to N receivers") to {@code ab_bpm_audit_record}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CcService {

    private final SmartEngine smartEngine;
    private final BpmExtensionAccessor extensionAccessor;
    private final BpmAuditService auditService;

    /**
     * Send a CC for the given task to the specified receiver user IDs.
     *
     * @param taskId          the active task ID
     * @param receiverUserIds receiver user IDs (numeric, must be non-empty)
     * @param comment         optional message body sent as notification content
     * @throws IllegalArgumentException if receiverUserIds is empty
     * @throws BusinessException        if the current user does not satisfy the CC policy
     */
    @Transactional
    public void cc(String taskId, List<Long> receiverUserIds, String comment) {
        if (receiverUserIds == null || receiverUserIds.isEmpty()) {
            throw new IllegalArgumentException("receiverUserIds must not be empty");
        }

        String currentUserId = BpmSecurityUtil.getCurrentUserId();
        Long currentUserIdLong = MetaContext.getCurrentUserId();
        String tenantIdStr = MetaContext.getCurrentTenantIdAsString();

        // 1. Resolve task → process instance + activity id
        TaskInstance task = smartEngine.getTaskQueryService().findOne(taskId, tenantIdStr);
        if (task == null) {
            throw new BusinessException("Task not found: " + taskId);
        }
        String processInstanceId = task.getProcessInstanceId();
        String activityId = task.getProcessDefinitionActivityId();

        ProcessInstance processInstance =
                smartEngine.getProcessQueryService().findById(processInstanceId, tenantIdStr);
        if (processInstance == null) {
            throw new BusinessException("Process instance not found: " + processInstanceId);
        }
        String processKey = processInstance.getProcessDefinitionId();

        // 2. Resolve CcPolicy (activity override > process default > ALL)
        CcPolicy policy = extensionAccessor.getCcPolicy(processKey, activityId);

        // 3. Identity gate
        String initiatorId = processInstance.getStartUserId();
        if (initiatorId == null) {
            initiatorId = auditService.findByProcessInstance(processInstanceId)
                    .stream()
                    .filter(r -> BpmAuditOperation.PROCESS_START.matches(r.getOperation()))
                    .map(r -> r.getDetails() != null
                            ? (String) r.getDetails().get("startUserId") : null)
                    .filter(uid -> uid != null && !uid.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        boolean isInitiator = currentUserId.equals(initiatorId);

        Long assigneeIdLong = parseLongSafely(task.getClaimUserId());
        boolean isAssignee = assigneeIdLong != null && assigneeIdLong.equals(currentUserIdLong);

        boolean allowed = switch (policy) {
            case INITIATOR -> isInitiator;
            case ASSIGNEE  -> isAssignee;
            case ALL       -> isInitiator || isAssignee;
        };
        if (!allowed) {
            throw new BusinessException(
                    "Current user does not satisfy cc policy: " + policy.code());
        }

        // 4. Delegate fan-out + storage + read tracking to SmartEngine.
        //    Use sendSingleNotification per receiver so we can set notification_type=cc
        //    (the bulk sendNotification overload does not accept a type parameter).
        for (Long receiverId : receiverUserIds) {
            smartEngine.getNotificationCommandService().sendSingleNotification(
                    processInstanceId,
                    taskId,
                    String.valueOf(currentUserIdLong),
                    String.valueOf(receiverId),
                    "$i18n:bpm.cc.inbox.title",
                    comment != null ? comment : "",
                    NotificationConstant.NotificationType.CC,
                    tenantIdStr);
        }

        // 5. Audit (AuraBoot business semantic)
        auditService.auditProcessOperation(
                BpmAuditOperation.CC.code(),
                processInstanceId,
                taskId,
                Map.of(
                        "receiverIds", receiverUserIds,
                        "comment", comment == null ? "" : comment,
                        "policy", policy.code()
                )
        );

        log.info("CC sent: instance={}, sender={}, receivers={}",
                processInstanceId, currentUserId, receiverUserIds);
    }

    private Long parseLongSafely(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }
}
