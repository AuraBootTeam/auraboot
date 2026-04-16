package com.auraboot.framework.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditOperation;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.entity.BpmAuditRecordEntity;
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
     * Minimal 2-step approval process: start → approve (userTask) → second (userTask) → end.
     * The second userTask ensures the process stays RUNNING after the first approval.
     */
    private static final String MINIMAL_BPMN_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:smart="http://smart.alibaba.com"
                         targetNamespace="http://auraboot.com/bpm">
                <process id="%s" isExecutable="true">
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

    public record ProcessSetup(String instanceId, String taskId) {}

    /**
     * Deploy a process with given key+policy, start it as the current user.
     * The initiator is the current username (matching BpmSecurityUtil.getCurrentUserId()).
     */
    public ProcessSetup startProcess(String keySuffix, WithdrawPolicy policy) {
        return startProcessWithInitiator(keySuffix, BpmSecurityUtil.getCurrentUserId(), policy);
    }

    /**
     * Deploy a process with given key+policy, start it as user identified by numeric userId.
     * Uses synthetic username "user-{userId}" as the initiator (matching switchCurrentUserTo).
     */
    public ProcessSetup startProcessAsUser(String keySuffix, Long userId, WithdrawPolicy policy) {
        return startProcessWithInitiator(keySuffix, "user-" + userId, policy);
    }

    /**
     * Deploy a process with the given initiatorId stored as PROCESS_INSTANCE_START_USER_ID.
     */
    private ProcessSetup startProcessWithInitiator(String keySuffix, String initiatorId,
            WithdrawPolicy policy) {
        String processKey = "test-withdraw-" + keySuffix + "-" + UniqueIdGenerator.generate();
        String tenantId = MetaContext.getCurrentTenantIdAsString();

        // 1. Deploy via ProcessDeploymentService (handles BPMN registration)
        String bpmn = String.format(MINIMAL_BPMN_TEMPLATE, processKey);
        ProcessDeploymentService.CreateProcessRequest req =
                new ProcessDeploymentService.CreateProcessRequest(
                        processKey, "Test Withdraw " + keySuffix, "Fixture process",
                        "test", bpmn, null, null, null);
        BpmProcessDefinition def = deploymentService.create(req);

        // 2. Set the withdraw policy before deploying
        def.setWithdrawPolicy(policy.code());
        processDefinitionMapper.updateById(def);

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

        log.debug("TestBpmFixture: started key={}, instanceId={}, taskId={}, initiator={}",
                processKey, instanceId, taskId, initiatorId);
        return new ProcessSetup(instanceId, taskId);
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
