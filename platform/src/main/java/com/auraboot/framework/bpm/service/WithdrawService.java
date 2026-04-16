package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditOperation;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.model.WithdrawPolicy;
import com.auraboot.framework.bpm.util.BpmSecurityUtil;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Handles process-instance withdrawal according to the process-level WithdrawPolicy.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code strict} — initiator only, before ANY approve. Rejected if any completed task
 *       in the instance was recorded with operation "task_approve" in the audit log.</li>
 *   <li>{@code loose}  — initiator only, anytime while the instance is still running.</li>
 *   <li>{@code none}   — disabled; always rejected.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawService {

    private final SmartEngine smartEngine;
    private final BpmProcessDefinitionMapper processDefinitionMapper;
    private final BpmAuditService auditService;

    /**
     * Withdraw a running process instance identified by a current task.
     *
     * @param taskId the ID of any active task belonging to the target instance
     * @param reason human-readable reason for withdrawal
     * @throws BusinessException if the withdrawal is not allowed under the policy
     */
    @Transactional
    public void withdraw(String taskId, String reason) {
        String currentUserId = BpmSecurityUtil.getCurrentUserId();
        String tenantId = MetaContext.getCurrentTenantIdAsString();

        // 1. Resolve task → process instance
        TaskInstance task = smartEngine.getTaskQueryService().findOne(taskId, tenantId);
        if (task == null) {
            throw new BusinessException("Task not found: " + taskId);
        }
        String processInstanceId = task.getProcessInstanceId();

        // 2. Resolve process instance and definition
        ProcessInstance processInstance =
                smartEngine.getProcessQueryService().findById(processInstanceId, tenantId);
        if (processInstance == null) {
            throw new BusinessException("Process instance not found: " + processInstanceId);
        }
        String processKey = processInstance.getProcessDefinitionId();

        BpmProcessDefinition def = processDefinitionMapper.findByProcessKey(
                MetaContext.getCurrentTenantId(), processKey);
        if (def == null) {
            throw new BusinessException("Process definition not found: " + processKey);
        }

        // 3. Policy gate
        WithdrawPolicy policy = WithdrawPolicy.fromCode(def.getWithdrawPolicy());
        if (policy == WithdrawPolicy.NONE) {
            throw new BusinessException("Withdraw is disabled for process: " + processKey);
        }

        // 4. Initiator check — look up via ProcessInstance.startUserId, with audit-log fallback.
        //    ProcessInstance.getStartUserId() is populated by SmartEngine from the
        //    PROCESS_INSTANCE_START_USER_ID variable set at process start time.
        //    The audit-log fallback covers migrated data and tests that record
        //    PROCESS_START separately. Both paths are single-source lookups — not
        //    the banned runtime fallback pattern (no multi-parser speculation).
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
        if (initiatorId == null || !currentUserId.equals(initiatorId)) {
            throw new BusinessException("Only the initiator can withdraw this process");
        }

        // 5. STRICT policy: reject if any task was previously approved
        //    Approved tasks are tracked via BpmAuditService (operation = "task_approve")
        if (policy == WithdrawPolicy.STRICT) {
            boolean anyApproved = auditService.findByProcessInstance(processInstanceId)
                    .stream()
                    .anyMatch(r -> BpmAuditOperation.TASK_APPROVE.matches(r.getOperation()));
            if (anyApproved) {
                throw new BusinessException(
                        "Process has already approved tasks; withdraw not allowed under strict policy");
            }
        }

        // 6. Terminate the process via SmartEngine abort
        //    The adapter's terminateProcess() is a stub over an in-memory map that
        //    is not populated by SmartEngine-started instances; call SmartEngine directly.
        smartEngine.getProcessCommandService().abort(processInstanceId, "WITHDRAWN", tenantId);

        // 7. Audit
        auditService.auditProcessOperation(
                BpmAuditOperation.WITHDRAW.code(),
                processInstanceId,
                taskId,
                Map.of(
                        "reason", reason != null ? reason : "",
                        "policy", policy.code(),
                        "userId", currentUserId
                )
        );

        log.info("Process withdrawn: instanceId={}, processKey={}, by user={}, reason={}",
                processInstanceId, processKey, currentUserId, reason);
    }
}
