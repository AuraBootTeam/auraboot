package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditOperation;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.entity.BpmCcRecord;
import com.auraboot.framework.bpm.mapper.BpmCcRecordMapper;
import com.auraboot.framework.bpm.model.CcPolicy;
import com.auraboot.framework.bpm.util.BpmSecurityUtil;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the CC (carbon copy) operation on a BPM task.
 *
 * <p>Policy semantics:
 * <ul>
 *   <li>{@code initiator} — only the process initiator may send CC.</li>
 *   <li>{@code assignee}  — only the current task assignee may send CC.</li>
 *   <li>{@code all}       — either initiator or assignee may send CC.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CcService {

    private final SmartEngine smartEngine;
    private final BpmProcessDefinitionMapper processDefinitionMapper;
    private final BpmCcRecordMapper ccRecordMapper;
    private final InboxService inboxService;
    private final BpmAuditService auditService;

    /**
     * Send a CC for the given task to the specified receivers.
     *
     * @param taskId          the active task ID
     * @param receiverUserIds numeric user IDs to notify
     * @param comment         optional comment
     * @return the persisted {@link BpmCcRecord}
     * @throws IllegalArgumentException if receiverUserIds is empty
     * @throws BusinessException        if the current user does not satisfy the CC policy
     */
    @Transactional
    public BpmCcRecord cc(String taskId, List<Long> receiverUserIds, String comment) {
        if (receiverUserIds == null || receiverUserIds.isEmpty()) {
            throw new IllegalArgumentException("receiverUserIds must not be empty");
        }

        String currentUserId = BpmSecurityUtil.getCurrentUserId();
        Long currentUserIdLong = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        String tenantIdStr = MetaContext.getCurrentTenantIdAsString();

        // 1. Resolve task → process instance
        TaskInstance task = smartEngine.getTaskQueryService().findOne(taskId, tenantIdStr);
        if (task == null) {
            throw new BusinessException("Task not found: " + taskId);
        }
        String processInstanceId = task.getProcessInstanceId();

        // 2. Resolve process instance and definition
        com.auraboot.smart.framework.engine.model.instance.ProcessInstance processInstance =
                smartEngine.getProcessQueryService().findById(processInstanceId, tenantIdStr);
        if (processInstance == null) {
            throw new BusinessException("Process instance not found: " + processInstanceId);
        }
        String processKey = processInstance.getProcessDefinitionId();

        BpmProcessDefinition def = processDefinitionMapper.findByProcessKey(tenantId, processKey);
        if (def == null) {
            throw new BusinessException("Process definition not found: " + processKey);
        }

        // 3. Policy gate
        CcPolicy policy = CcPolicy.fromCode(def.getCcPolicy());

        // Resolve initiator: prefer processInstance.startUserId, fall back to audit log
        String initiatorId = processInstance.getStartUserId();
        if (initiatorId == null) {
            initiatorId = auditService.findByProcessInstance(processInstanceId)
                    .stream()
                    .filter(r -> BpmAuditOperation.PROCESS_START.matches(r.getOperation()))
                    .map(r -> r.getDetails() != null
                            ? (String) r.getDetails().get("startUserId")
                            : null)
                    .filter(uid -> uid != null && !uid.isBlank())
                    .findFirst()
                    .orElse(null);
        }
        boolean isInitiator = currentUserId.equals(initiatorId);

        // Resolve assignee: prefer claimUserId (claimed user), fall back to task variables
        String assigneeStr = task.getClaimUserId();
        Long assigneeIdLong = parseLongSafely(assigneeStr);
        boolean isAssignee = assigneeIdLong != null && assigneeIdLong.equals(currentUserIdLong);

        boolean allowed = switch (policy) {
            case INITIATOR -> isInitiator;
            case ASSIGNEE -> isAssignee;
            case ALL -> isInitiator || isAssignee;
        };
        if (!allowed) {
            throw new BusinessException(
                    "Current user does not satisfy cc policy: " + policy.code());
        }

        // 4. Persist CC record
        BpmCcRecord record = new BpmCcRecord();
        record.setPid(UlidGenerator.generate());
        record.setTenantId(tenantId);
        record.setProcessInstanceId(processInstanceId);
        record.setTaskId(taskId);
        record.setSenderId(currentUserIdLong);
        record.setReceiverUserIds(receiverUserIds);
        record.setComment(comment);
        record.setReadState(new HashMap<>());
        Instant now = Instant.now();
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        record.setDeletedFlag(false);
        ccRecordMapper.insert(record);

        // 5. Push Inbox notifications to each receiver
        for (Long receiverId : receiverUserIds) {
            InboxItem item = new InboxItem();
            item.setTenantId(tenantId);
            item.setUserId(receiverId);
            item.setItemType("bpm_cc");
            item.setClientItemId("bpm_cc_" + record.getId() + "_" + receiverId);
            // i18n reference — rendered by UI; processKey provides context via subtitle
            item.setTitle("$i18n:bpm.cc.inbox.title");
            item.setSubtitle(processKey + (comment != null && !comment.isBlank()
                    ? ": " + comment : ""));
            item.setSourceType("bpm");
            item.setSourceId(processInstanceId);
            item.setDeepLink("/p/bpm/process/" + processInstanceId);
            item.setCreatedAt(now);
            inboxService.createItem(item);
        }

        // 6. Audit
        auditService.auditProcessOperation(
                BpmAuditOperation.CC.code(),
                processInstanceId,
                taskId,
                Map.of(
                        "receiverIds", receiverUserIds,
                        "comment", comment == null ? "" : comment,
                        "ccRecordId", record.getId()
                )
        );

        log.info("CC sent: instance={}, sender={}, receivers={}",
                processInstanceId, currentUserId, receiverUserIds);
        return record;
    }

    private Long parseLongSafely(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
