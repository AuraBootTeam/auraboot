package com.auraboot.framework.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditOperation;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.entity.BpmAuditRecordEntity;
import com.auraboot.framework.bpm.model.CcPolicy;
import com.auraboot.framework.bpm.model.WithdrawPolicy;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.util.BpmSecurityUtil;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test helper component for BPM integration tests.
 *
 * <p>Deploys minimal single-approval-step BPMN processes and exposes convenience
 * methods to start instances, simulate approvals, and query state via SmartEngine.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TestBpmFixture {

    /**
     * Minimal 2-step approval process: start → approval (userTask) → second_approval (userTask) → end.
     * Process and task have <smart:properties> with aura.* keys for policy testing.
     * Format args: %1$s = processKey, %2$s = withdrawPolicy code, %3$s = ccPolicy code.
     */
    private static final String MINIMAL_BPMN_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:smart="http://smartengine.org/schema/process"
                         targetNamespace="http://auraboot.com/bpm">
                <process id="%1$s" isExecutable="true">
                    <extensionElements>
                        <smart:properties>
                            <smart:property name="aura.withdrawPolicy" value="%2$s"/>
                            <smart:property name="aura.ccPolicy" value="%3$s"/>
                        </smart:properties>
                    </extensionElements>
                    <startEvent id="start"/>
                    <userTask id="approval" name="First Approval"
                              smart:assigneeType="user"
                              smart:assigneeId="system"/>
                    <userTask id="second_approval" name="Second Approval"
                              smart:assigneeType="user"
                              smart:assigneeId="system"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approval"/>
                    <sequenceFlow id="f2" sourceRef="approval" targetRef="second_approval"/>
                    <sequenceFlow id="f3" sourceRef="second_approval" targetRef="end"/>
                </process>
            </definitions>
            """;

    private final ProcessDeploymentService deploymentService;
    private final BpmProcessDefinitionMapper processDefinitionMapper;
    private final BpmAuditService auditService;
    private final SmartEngine smartEngine;

    /**
     * Process setup result.
     * assigneeId is set to 0L for processes started without an explicit assignee;
     * CcPolicy-based setup populates it with a real test user id.
     */
    public record ProcessSetup(String instanceId, String taskId, Long assigneeId) {
        /** Backwards-compat constructor for callers that don't need assigneeId. */
        public ProcessSetup(String instanceId, String taskId) {
            this(instanceId, taskId, 0L);
        }
    }

    /**
     * Deploy a process with given key+policy, start it as the current user.
     * The initiator is the current username (matching BpmSecurityUtil.getCurrentUserId()).
     */
    public ProcessSetup startProcess(String keySuffix, WithdrawPolicy policy) {
        return startProcessWithInitiator(keySuffix, BpmSecurityUtil.getCurrentUserId(),
                policy, CcPolicy.ALL);
    }

    /**
     * Deploy a process with the given CcPolicy, start it as the current user.
     * The fixture uses a fixed test assignee user id (888L) so CcPolicy=assignee tests work.
     */
    public ProcessSetup startProcess(String keySuffix, CcPolicy ccPolicy) {
        return startProcessWithInitiator(keySuffix, BpmSecurityUtil.getCurrentUserId(),
                WithdrawPolicy.STRICT, ccPolicy);
    }

    /**
     * Deploy a process with given key+policy, start it as user identified by numeric userId.
     * Uses synthetic username "user-{userId}" as the initiator (matching switchCurrentUserTo).
     */
    public ProcessSetup startProcessAsUser(String keySuffix, Long userId, WithdrawPolicy policy) {
        return startProcessWithInitiator(keySuffix, "user-" + userId, policy, CcPolicy.ALL);
    }

    /**
     * Deploy a minimal BPMN process under the exact given processKey (no suffix appended).
     * Uses ProcessDeploymentService — same path as production.
     *
     * @param processKey the exact process key to deploy (must be stable across duplicate checks)
     */
    public void deployProcess(String processKey) {
        String bpmn = String.format(MINIMAL_BPMN_TEMPLATE,
                processKey, WithdrawPolicy.STRICT.code(), CcPolicy.ALL.code());
        ProcessDeploymentService.CreateProcessRequest req =
                new ProcessDeploymentService.CreateProcessRequest(
                        processKey, "Test Action " + processKey, "Fixture process",
                        "test", bpmn, null, null, null);
        BpmProcessDefinition def = deploymentService.create(req);
        deploymentService.deploy(def.getPid());
        log.debug("TestBpmFixture.deployProcess: deployed key={}", processKey);
    }

    /**
     * Deploy a process with the given initiatorId stored as PROCESS_INSTANCE_START_USER_ID.
     */
    private ProcessSetup startProcessWithInitiator(String keySuffix, String initiatorId,
            WithdrawPolicy withdrawPolicy, CcPolicy ccPolicy) {
        String processKey = "test-withdraw-" + keySuffix + "-" + UniqueIdGenerator.generate();
        String tenantId = MetaContext.getCurrentTenantIdAsString();

        // 1. Deploy via ProcessDeploymentService (handles BPMN registration)
        String bpmn = String.format(MINIMAL_BPMN_TEMPLATE,
                processKey, withdrawPolicy.code(), ccPolicy.code());
        ProcessDeploymentService.CreateProcessRequest req =
                new ProcessDeploymentService.CreateProcessRequest(
                        processKey, "Test Withdraw " + keySuffix, "Fixture process",
                        "test", bpmn, null, null, null);
        BpmProcessDefinition def = deploymentService.create(req);

        deploymentService.deploy(def.getPid());

        // 3. Start the process with explicit initiator stored in variables
        String processVersion = resolveVersion(processKey, tenantId);
        Map<String, Object> vars = new HashMap<>();
        vars.put(RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID, initiatorId);
        vars.put(RequestMapSpecialKeyConstant.TENANT_ID, tenantId);
        vars.put(RequestMapSpecialKeyConstant.PROCESS_BIZ_UNIQUE_ID, "biz-" + keySuffix);

        ProcessInstance instance = smartEngine.getProcessCommandService()
                .start(processKey, processVersion, vars);
        String instanceId = instance.getInstanceId();

        // Explicitly record process start audit so WithdrawService can resolve the initiator.
        // We use auditProcessOperation directly (not recordProcessStart) because recordProcessStart
        // does NOT include startUserId in details — but WithdrawService looks for details.startUserId
        // from the PROCESS_START audit record to resolve the initiator when getProcessInitiator() is null.
        auditService.auditProcessOperation(BpmAuditOperation.PROCESS_START.code(), instanceId, null,
                Map.of("startUserId", initiatorId,
                       "processDefinitionId", processKey,
                       "businessKey", "biz-" + keySuffix));

        // 4. Retrieve the first active task
        List<TaskInstance> tasks = smartEngine.getTaskQueryService()
                .findAllPendingTaskList(instanceId, tenantId);
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalStateException(
                    "No active task found after starting process: " + processKey);
        }
        String taskId = tasks.get(0).getInstanceId();

        // Assignee id: use the claimUserId from the task if present; otherwise use a fixed
        // test user id (888L) so cc-policy tests can switch to "the assignee".
        Long assigneeId = 888L;
        TaskInstance firstTask = tasks.get(0);
        if (firstTask.getClaimUserId() != null && !firstTask.getClaimUserId().isBlank()) {
            try { assigneeId = Long.parseLong(firstTask.getClaimUserId()); }
            catch (NumberFormatException ignored) {}
        }

        log.debug("TestBpmFixture: started key={}, instanceId={}, taskId={}, initiator={}, assigneeId={}",
                processKey, instanceId, taskId, initiatorId, assigneeId);
        return new ProcessSetup(instanceId, taskId, assigneeId);
    }

    /**
     * Simulate approving a task by completing it with _action=approve.
     * This causes BpmAuditService to record "task_approve" which WithdrawService checks.
     */
    public void approveTask(String taskId, String comment) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        TaskInstance task = smartEngine.getTaskQueryService().findOne(taskId, tenantId);
        if (task == null) {
            throw new IllegalStateException("Task not found for approval: " + taskId);
        }
        // Record approve audit entry (mirrors what TaskService.approveTask does)
        auditService.auditTaskOperation(BpmAuditOperation.TASK_APPROVE.code(), taskId,
                task.getProcessInstanceId(),
                BpmSecurityUtil.getCurrentUserId(), null, comment, null);

        // Complete the task via SmartEngine
        Map<String, Object> vars = new HashMap<>();
        vars.put("_action", "approve");
        vars.put("_comment", comment != null ? comment : "");
        vars.put(RequestMapSpecialKeyConstant.TENANT_ID, tenantId);
        smartEngine.getTaskCommandService().complete(taskId, vars);
    }

    /**
     * Return the ID of the first active task for the given process instance, or null if none.
     */
    public String currentTaskId(String instanceId) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        List<TaskInstance> tasks = smartEngine.getTaskQueryService()
                .findAllPendingTaskList(instanceId, tenantId);
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }
        return tasks.get(0).getInstanceId();
    }

    /**
     * Return the process status as a lowercase string.
     * Maps SmartEngine InstanceStatus.aborted → "withdrawn" for semantic clarity.
     */
    public String getProcessStatus(String instanceId) {
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        ProcessInstance instance = smartEngine.getProcessQueryService().findById(instanceId, tenantId);
        if (instance == null) {
            return "not_found";
        }
        String status = instance.getStatus() != null ? instance.getStatus().name().toLowerCase() : "unknown";
        // Map SmartEngine's "aborted" to our semantic "withdrawn"
        return "aborted".equals(status) ? "withdrawn" : status;
    }

    /**
     * Return all audit records for the given process instance.
     */
    public List<BpmAuditRecordEntity> findAuditRecords(String instanceId) {
        return auditService.findByProcessInstance(instanceId);
    }

    /**
     * Switch the current user in MetaContext by numeric ID.
     * Sets username to "user-{userId}" — must match startProcessAsUser synthetic username.
     */
    public void switchCurrentUserTo(Long userId) {
        MetaContext.setContext(
                MetaContext.getCurrentTenantId(),
                userId,
                MetaContext.getCurrentUserPid(),
                "user-" + userId
        );
    }

    /** Resolve the deployed process definition version string. */
    private String resolveVersion(String processKey, String tenantId) {
        BpmProcessDefinition def = processDefinitionMapper.findByProcessKey(
                Long.valueOf(tenantId), processKey);
        if (def == null || def.getDeploymentId() == null) {
            throw new IllegalStateException("Process not deployed: " + processKey);
        }
        // deploymentId format is "processKey:version"
        String[] parts = def.getDeploymentId().split(":");
        if (parts.length < 2) {
            return "1.0.0";
        }
        return parts[1] + ".0.0";
    }
}
