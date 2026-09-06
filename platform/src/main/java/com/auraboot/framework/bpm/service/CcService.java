package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditOperation;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.extension.BpmExtensionAccessor;
import com.auraboot.framework.bpm.model.CcPolicy;
import com.auraboot.framework.bpm.util.BpmSecurityUtil;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.smart.framework.engine.SmartEngine;
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
 * <p>Storage and per-receiver fan-out is owned by AuraBoot's business notify
 * store ({@code ab_bpm_notify_record}). SmartEngine does not own the BPM CC
 * inbox. AuraBoot also writes a business-semantic audit record ("I executed cc
 * to N receivers") to {@code ab_bpm_audit_record}.
 *
 * <p><strong>Transactional semantics:</strong> the per-receiver loop and the
 * audit write run inside a single Spring {@code @Transactional} boundary.
 * Product notification writes participate in the same transaction, so a
 * failure mid-loop rolls back any notifications already written together with
 * the audit record (all-or-nothing). Bad-input failures (null receiver entry,
 * empty list) are rejected before any notify write.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CcService {

    private final SmartEngine smartEngine;
    private final BpmExtensionAccessor extensionAccessor;
    private final BpmAuditService auditService;
    private final UserService userService;
    private final TaskService taskService;
    private final BpmNotifyService notifyService;

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
    public void cc(String taskId, List<String> receiverUserPids, String comment) {
        cc(taskId, receiverUserPids, comment, "TASK_API");
    }

    @Transactional
    public void cc(String taskId, List<String> receiverUserPids, String comment, String sourceType) {
        // Receivers are addressed by ab_user.pid (ULID) — the identity the
        // frontend MemberPicker carries. Pids resolve to numeric ids here so
        // the notification fan-out keeps its numeric contract (previously the
        // frontend's Number(pid) produced NaN/null and callers 500'd).
        if (receiverUserPids == null || receiverUserPids.isEmpty()) {
            throw new IllegalArgumentException("receiverUserIds must not be empty");
        }
        if (receiverUserPids.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("receiverUserIds must not contain null entries");
        }
        List<Long> receiverUserIds = receiverUserPids.stream().map(pid -> {
            User user = userService.findByPid(pid);
            if (user == null) {
                throw new BusinessException("Unknown receiver user pid: " + pid);
            }
            return user.getId();
        }).toList();
        ccForUserIds(taskId, receiverUserIds, comment, sourceType, null);
    }

    /**
     * CC with already-resolved numeric user ids, attributed to the current
     * user ({@code TASK_API}) so the cc-policy identity gate applies. System
     * callers (automation / event-policy) MUST pass their own sourceType via
     * the explicit overload — branding a call {@code AUTOMATION} is what
     * skips the gate, so it is never a default.
     */
    public void ccForUserIds(String taskId, List<Long> receiverUserIds, String comment) {
        ccForUserIds(taskId, receiverUserIds, comment, "TASK_API", null);
    }

    @Transactional
    public void ccForUserIds(
            String taskId,
            List<Long> receiverUserIds,
            String comment,
            String sourceType,
            String dedupKey) {
        if (receiverUserIds == null || receiverUserIds.isEmpty()) {
            throw new IllegalArgumentException("receiverUserIds must not be empty");
        }
        if (receiverUserIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("receiverUserIds must not contain null entries");
        }

        // BPM tasks and process instances are persisted in DATABASE storage mode.
        // Automation synthesized flows force the CUSTOM (in-session) storage mode
        // for their own runtime, so when a cc_task action drives this method from
        // an automation thread the engine queries below would otherwise consult
        // the empty in-session view and report the task missing. Pin DATABASE for
        // the engine lookups and restore the caller's mode afterwards.
        com.auraboot.smart.framework.engine.storage.StorageMode previousStorageMode =
            com.auraboot.smart.framework.engine.storage.StorageModeHolder.get();
        com.auraboot.smart.framework.engine.storage.StorageModeHolder.set(
            com.auraboot.smart.framework.engine.storage.StorageMode.DATABASE);
        try {
            doCcForUserIds(taskId, receiverUserIds, comment, sourceType, dedupKey);
        } finally {
            com.auraboot.smart.framework.engine.storage.StorageModeHolder.set(previousStorageMode);
        }
    }

    private void doCcForUserIds(
            String taskId,
            List<Long> receiverUserIds,
            String comment,
            String sourceType,
            String dedupKey) {
        Long currentUserIdLong = MetaContext.getCurrentUserId();
        String currentUserId = BpmSecurityUtil.getCurrentUserId();
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
        // System actors (automation runners / event-policy evaluation) execute
        // policy-defined CC actions on the engine's own lifecycle events. They
        // are not a user at the task, so the task-actor identity gate does not
        // apply to them; the sourceType is fixed server-side per call site and
        // cannot be chosen by REST callers.
        boolean systemActor = "AUTOMATION".equals(sourceType) || "EVENT_POLICY".equals(sourceType);
        if (currentUserIdLong != null && !systemActor) {
            boolean isInitiator = initiatorId != null
                    && (initiatorId.equals(String.valueOf(currentUserIdLong))
                            || initiatorId.equals(currentUserId));

            // TaskService owns completion identity: claimed exclusivity, direct
            // assignees, candidates, and role/group visibility. CC must not use
            // a narrower claim-only check.
            boolean isAssignee = taskService.canCompleteTask(
                    task, currentUserId);
            if (!isAssignee) {
                Long claimIdLong = parseLongSafely(task.getClaimUserId());
                isAssignee = claimIdLong != null && claimIdLong.equals(currentUserIdLong);
            }

            boolean allowed = switch (policy) {
                case INITIATOR -> isInitiator;
                case ASSIGNEE  -> isAssignee;
                case ALL       -> isInitiator || isAssignee;
            };
            if (!allowed) {
                throw new BusinessException(
                        "Current user does not satisfy cc policy: " + policy.code());
            }
        } else if (!"AUTOMATION".equals(sourceType) && !"EVENT_POLICY".equals(sourceType)) {
            throw new BusinessException("Authenticated user required for cc policy");
        }

        // 4. Fan out to the product-owned BPM notify store. Read state and the
        //    TaskCenter inbox use this same table.
        notifyService.sendCarbonCopy(
                taskId,
                processInstanceId,
                currentUserIdLong,
                receiverUserIds,
                comment != null ? comment : "",
                "$i18n:bpm.cc.inbox.title",
                sourceType,
                dedupKey);

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
                processInstanceId, currentUserIdLong, receiverUserIds);
    }

    private Long parseLongSafely(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }
}
