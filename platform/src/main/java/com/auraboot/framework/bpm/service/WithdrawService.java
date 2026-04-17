package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditOperation;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.extension.BpmExtensionAccessor;
import com.auraboot.framework.bpm.model.WithdrawPolicy;
import com.auraboot.framework.bpm.util.BpmSecurityUtil;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Handles process-instance withdrawal according to the process-level WithdrawPolicy
 * declared in BPMN <smart:properties> under aura.withdrawPolicy.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code strict} — initiator only, before ANY approve.</li>
 *   <li>{@code loose}  — initiator only, anytime while the instance is still running.</li>
 *   <li>{@code none}   — disabled; always rejected.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawService {

    private final SmartEngine smartEngine;
    private final BpmExtensionAccessor extensionAccessor;
    private final BpmAuditService auditService;

    /**
     * Withdraw a running process instance identified by a current task.
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

        ProcessInstance processInstance =
                smartEngine.getProcessQueryService().findById(processInstanceId, tenantId);
        if (processInstance == null) {
            throw new BusinessException("Process instance not found: " + processInstanceId);
        }
        String processKey = processInstance.getProcessDefinitionId();

        // 2. Policy gate (read from BPMN extension; defaults to STRICT when absent)
        WithdrawPolicy policy = extensionAccessor.getWithdrawPolicy(processKey);
        if (policy == WithdrawPolicy.NONE) {
            throw new BusinessException("Withdraw is disabled for process: " + processKey);
        }

        // 3. Initiator check
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
        if (initiatorId == null || !currentUserId.equals(initiatorId)) {
            throw new BusinessException("Only the initiator can withdraw this process");
        }

        // 4. STRICT: reject if any task previously approved
        if (policy == WithdrawPolicy.STRICT) {
            boolean anyApproved = auditService.findByProcessInstance(processInstanceId)
                    .stream()
                    .anyMatch(r -> BpmAuditOperation.TASK_APPROVE.matches(r.getOperation()));
            if (anyApproved) {
                throw new BusinessException(
                        "Process has already approved tasks; withdraw not allowed under strict policy");
            }
        }

        // 5. Abort via SmartEngine (terminate process)
        smartEngine.getProcessCommandService().abort(processInstanceId, "WITHDRAWN", tenantId);

        // 6. Audit
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
