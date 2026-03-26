package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.chain.CommandChainDefinition.*;
import com.auraboot.framework.bpm.entity.ApprovalTask;
import com.auraboot.framework.bpm.event.ApprovalEvent;
import com.auraboot.framework.bpm.entity.ChainExecution;
import com.auraboot.framework.bpm.mapper.ApprovalTaskMapper;
import com.auraboot.framework.bpm.mapper.ChainExecutionMapper;
import com.auraboot.framework.bpm.service.AssigneeResolverService;
import com.auraboot.framework.bpm.service.ExecutionLogService;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Core engine for APPROVAL-mode command chains.
 * Walks the chain node graph directly (bypasses SmartEngine),
 * persists state in ab_chain_execution, and creates ab_approval_task records at UserTask nodes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalChainExecutor {

    private final ChainExecutionMapper chainExecutionMapper;
    private final ApprovalTaskMapper approvalTaskMapper;
    private final AssigneeResolverService assigneeResolverService;
    private final CommandExecutor commandExecutor;
    private final ExecutionLogService executionLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    private final ExpressionParser spelParser = new SpelExpressionParser();
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * Start a new approval chain execution.
     */
    @Transactional
    public CommandChainResult startChain(CommandChainDefinition chain, String businessKey,
                                          Map<String, Object> payload) {
        String chainExecPid = UlidGenerator.generate();
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        // 1. Create chain execution record
        Map<String, Object> chainDefMap = objectMapper.convertValue(chain, Map.class);
        ChainExecution exec = ChainExecution.builder()
                .pid(chainExecPid)
                .tenantId(tenantId)
                .processKey(chain.getProcessKey())
                .businessKey(businessKey)
                .chainMode("approval")
                .status(StatusConstants.RUNNING)
                .processVariables(new HashMap<>(payload))
                .stepResults(new HashMap<>())
                .chainDefinition(chainDefMap)
                .startedAt(Instant.now())
                .createdBy(userId)
                .updatedBy(userId)
                .build();
        chainExecutionMapper.insert(exec);

        // 2. Inject chain metadata into process variables
        Map<String, Object> processVars = new HashMap<>(payload);
        processVars.put("_chain_mode", "approval");
        processVars.put("_chain_execution_id", chainExecPid);
        processVars.put("_chain_business_key", businessKey);
        processVars.put("_startUserId", userId);

        // 3. Find start event and begin execution
        String startNodeId = findStartEventId(chain);
        String firstNodeId = findNextNodeId(startNodeId, chain);

        return executeUntilPause(chainExecPid, chain, firstNodeId, processVars, new HashMap<>());
    }

    /**
     * Walk the chain graph, executing nodes until a userTask is hit or chain ends.
     */
    @Transactional
    public CommandChainResult executeUntilPause(String chainExecPid, CommandChainDefinition chain,
                                                 String startFromNodeId, Map<String, Object> processVars,
                                                 Map<String, Object> stepResults) {
        String currentNodeId = startFromNodeId;

        while (currentNodeId != null) {
            ChainNode node = findNodeById(currentNodeId, chain);
            if (node == null) {
                return failChain(chainExecPid, "Node not found: " + currentNodeId, processVars, stepResults);
            }

            String nodeType = node.getType();

            switch (nodeType) {
                case "endEvent" -> {
                    return completeChain(chainExecPid, processVars, stepResults, chain);
                }
                case "serviceTask" -> {
                    try {
                        executeServiceTask(chainExecPid, node, processVars, stepResults);
                    } catch (Exception e) {
                        log.error("ServiceTask {} failed: {}", currentNodeId, e.getMessage(), e);
                        return failChain(chainExecPid, "ServiceTask " + currentNodeId + " failed: " + e.getMessage(),
                                processVars, stepResults);
                    }
                    currentNodeId = findNextNodeId(currentNodeId, chain);
                }
                case "exclusiveGateway" -> {
                    currentNodeId = evaluateExclusiveGateway(currentNodeId, chain, processVars);
                }
                case "userTask" -> {
                    return suspendAtUserTask(chainExecPid, node, chain, processVars, stepResults);
                }
                default -> {
                    // Skip unknown node types (e.g., startEvent reached unexpectedly)
                    currentNodeId = findNextNodeId(currentNodeId, chain);
                }
            }
        }

        return failChain(chainExecPid, "Chain ended without reaching endEvent", processVars, stepResults);
    }

    /**
     * Handle approval submission (approve or reject).
     */
    @Transactional
    public CommandChainResult handleApproval(String taskPid, Long approverId, String outcome,
                                              String comment, Map<String, Object> formData) {
        return handleApproval(taskPid, approverId, outcome, comment, formData, null, null);
    }

    /**
     * Handle approval with optional signature and attachments.
     */
    @Transactional
    public CommandChainResult handleApproval(String taskPid, Long approverId, String outcome,
                                              String comment, Map<String, Object> formData,
                                              String signature, List<Map<String, Object>> attachments) {
        // 1. Load and validate task
        ApprovalTask task = approvalTaskMapper.selectOne(
                new QueryWrapper<ApprovalTask>().eq("pid", taskPid));
        if (task == null) {
            throw new IllegalArgumentException("Approval task not found: " + taskPid);
        }

        // Optimistic lock: only update if still PENDING
        UpdateWrapper<ApprovalTask> updateWrapper = new UpdateWrapper<ApprovalTask>()
                .set("status", outcome.equals("approved") ? "approved" : "rejected")
                .set("actual_approver_id", approverId)
                .set("approval_comment", comment)
                .set("approval_data", formData != null ? toJsonString(formData) : null)
                .set("signature", signature)
                .set("attachments", attachments != null ? toJsonString(attachments) : null)
                .set("completed_at", Instant.now())
                .set("updated_at", Instant.now())
                .eq("pid", taskPid)
                .eq("status", StatusConstants.PENDING);
        int updated = approvalTaskMapper.update(null, updateWrapper);

        if (updated == 0) {
            throw new IllegalStateException("Task already completed or does not exist: " + taskPid);
        }

        // 2. Verify approver is in assignee list
        if (!task.getAssigneeUserIds().contains(approverId)) {
            throw new SecurityException("User " + approverId + " is not an assignee of task " + taskPid);
        }

        // 3. Handle ALL strategy — check if all assignees approved
        if ("all".equals(task.getAssigneeStrategy()) && StatusConstants.APPROVED.equals(outcome)) {
            // Reload to check approval_data for tracking
            // For simplicity in Phase 1: ALL requires sequential re-approval by each user
            // (full countersign tracking deferred to Phase 2)
            log.info("ALL strategy approval by user {} for task {}", approverId, taskPid);
        }

        // 4. Load chain execution
        ChainExecution exec = chainExecutionMapper.selectOne(
                new QueryWrapper<ChainExecution>().eq("pid", task.getChainExecutionId()));
        if (exec == null) {
            throw new IllegalStateException("Chain execution not found: " + task.getChainExecutionId());
        }

        String chainExecPid = exec.getPid();
        Map<String, Object> processVars = exec.getProcessVariables() != null
                ? new HashMap<>(exec.getProcessVariables()) : new HashMap<>();
        Map<String, Object> stepResults = exec.getStepResults() != null
                ? new HashMap<>(exec.getStepResults()) : new HashMap<>();

        // 5. Merge approval data into process variables
        String nodeId = task.getChainNodeId();
        processVars.put("_approval_" + nodeId + "_outcome", outcome);
        processVars.put("_approval_" + nodeId + "_comment", comment);
        if (formData != null) {
            processVars.put("_approval_" + nodeId + "_data", formData);
        }

        if (StatusConstants.REJECTED.equals(outcome)) {
            eventPublisher.publishEvent(ApprovalEvent.completed(
                    MetaContext.getCurrentTenantId(), task.getProcessKey(), chainExecPid,
                    nodeId, taskPid, "rejected", approverId, comment));
            return handleRejection(chainExecPid, task, processVars, stepResults, comment);
        }

        // 6. Resume chain from next node
        CommandChainDefinition chain = restoreChainDefinition(exec);
        String nextNodeId = findNextNodeId(nodeId, chain);

        // Update chain status to RUNNING
        chainExecutionMapper.update(null,
                new UpdateWrapper<ChainExecution>()
                        .set("status", StatusConstants.RUNNING)
                        .set("process_variables", toJsonString(processVars))
                        .set("step_results", toJsonString(stepResults))
                        .set("updated_at", Instant.now())
                        .eq("pid", chainExecPid));

        executionLogService.logStateChange(chainExecPid, "suspended", "running",
                "Approved by user " + approverId);

        eventPublisher.publishEvent(ApprovalEvent.completed(
                MetaContext.getCurrentTenantId(), chain.getProcessKey(), chainExecPid,
                nodeId, taskPid, "approved", approverId, comment));

        return executeUntilPause(chainExecPid, chain, nextNodeId, processVars, stepResults);
    }

    /**
     * Reassign a pending task to new assignees.
     */
    @Transactional
    public void reassignTask(String taskPid, Long requesterId, List<Long> newAssigneeIds) {
        ApprovalTask task = approvalTaskMapper.selectOne(
                new QueryWrapper<ApprovalTask>().eq("pid", taskPid));
        if (task == null || !StatusConstants.PENDING.equals(task.getStatus())) {
            throw new IllegalStateException("Task not found or not pending: " + taskPid);
        }

        List<Long> oldAssignees = task.getAssigneeUserIds();

        approvalTaskMapper.update(null,
                new UpdateWrapper<ApprovalTask>()
                        .set("assignee_user_ids", toJsonString(newAssigneeIds))
                        .set("updated_at", Instant.now())
                        .eq("pid", taskPid));

        eventPublisher.publishEvent(ApprovalEvent.reassigned(
                task.getTenantId(), task.getProcessKey(), task.getChainExecutionId(),
                task.getChainNodeId(), taskPid, oldAssignees, newAssigneeIds));

        log.info("Task {} reassigned by {} to {}", taskPid, requesterId, newAssigneeIds);
    }

    // ==================== Private helpers ====================

    private CommandChainResult suspendAtUserTask(String chainExecPid, ChainNode userTaskNode,
                                                  CommandChainDefinition chain,
                                                  Map<String, Object> processVars,
                                                  Map<String, Object> stepResults) {
        ChainNodeData data = userTaskNode.getData();
        String nodeId = userTaskNode.getId();

        // 1. Resolve assignees
        String ruleType = data.getAssigneeRuleType();
        Map<String, Object> ruleConfig = data.getAssigneeRuleConfig() != null
                ? data.getAssigneeRuleConfig() : Map.of();
        List<String> assigneeStrIds = assigneeResolverService.resolve(ruleType, ruleConfig, processVars);

        if (assigneeStrIds == null || assigneeStrIds.isEmpty()) {
            return failChain(chainExecPid,
                    "No assignees resolved for userTask " + nodeId + " (ruleType=" + ruleType + ")",
                    processVars, stepResults);
        }

        List<Long> assigneeIds = assigneeStrIds.stream()
                .map(Long::valueOf)
                .toList();

        // 2. Resolve task title template
        String taskTitle = data.getTaskTitle() != null
                ? resolveTemplateString(data.getTaskTitle(), processVars) : "Approval Required";

        // 3. Calculate deadline
        Instant deadlineAt = null;
        if (data.getDeadline() != null && !data.getDeadline().isBlank()) {
            try {
                Duration duration = Duration.parse(data.getDeadline());
                deadlineAt = Instant.now().plus(duration);
            } catch (Exception e) {
                log.warn("Invalid deadline duration '{}': {}", data.getDeadline(), e.getMessage());
            }
        }

        // 4. Create approval task
        String taskPid = UlidGenerator.generate();
        Long tenantId = MetaContext.getCurrentTenantId();

        ApprovalTask task = ApprovalTask.builder()
                .pid(taskPid)
                .tenantId(tenantId)
                .chainExecutionId(chainExecPid)
                .chainNodeId(nodeId)
                .processKey(chain.getProcessKey())
                .businessKey((String) processVars.get("_chain_business_key"))
                .taskTitle(taskTitle)
                .taskDescription(data.getLabel())
                .priority("normal")
                .status(StatusConstants.PENDING)
                .assigneeStrategy(data.getAssigneeStrategy() != null ? data.getAssigneeStrategy() : "any")
                .assigneeUserIds(assigneeIds)
                .assigneeRuleType(ruleType)
                .assigneeRuleConfig(ruleConfig.isEmpty() ? null : ruleConfig)
                .formRef(data.getFormRef())
                .formSnapshot(buildFormSnapshot(processVars))
                .deadlineAt(deadlineAt)
                .createdBy(MetaContext.getCurrentUserId())
                .updatedBy(MetaContext.getCurrentUserId())
                .build();
        approvalTaskMapper.insert(task);

        // 5. Update chain execution to SUSPENDED
        chainExecutionMapper.update(null,
                new UpdateWrapper<ChainExecution>()
                        .set("status", StatusConstants.SUSPENDED)
                        .set("current_node_id", nodeId)
                        .set("process_variables", toJsonString(processVars))
                        .set("step_results", toJsonString(stepResults))
                        .set("updated_at", Instant.now())
                        .eq("pid", chainExecPid));

        executionLogService.logNodeStart(chainExecPid, nodeId, "userTask",
                Map.of("taskPid", taskPid, "assignees", assigneeIds.toString(), "title", taskTitle));
        executionLogService.logStateChange(chainExecPid, "running", "suspended",
                "Waiting for approval at node " + nodeId);

        // 6. Publish event
        eventPublisher.publishEvent(ApprovalEvent.taskCreated(
                tenantId, chain.getProcessKey(), chainExecPid, nodeId, taskPid, assigneeIds, taskTitle));

        log.info("Chain {} suspended at userTask {}, approval task {} created for {}",
                chainExecPid, nodeId, taskPid, assigneeIds);

        return CommandChainResult.builder()
                .success(true)
                .chainExecutionId(chainExecPid)
                .chainExecutionPid(chainExecPid)
                .processKey(chain.getProcessKey())
                .businessKey((String) processVars.get("_chain_business_key"))
                .chainMode(ChainMode.APPROVAL)
                .status(StatusConstants.SUSPENDED)
                .approvalTaskPid(taskPid)
                .stepResults(stepResults)
                .build();
    }

    private void executeServiceTask(String chainExecPid, ChainNode node,
                                     Map<String, Object> processVars,
                                     Map<String, Object> stepResults) {
        ChainNodeData data = node.getData();
        String nodeId = node.getId();
        String commandCode = data.getCommandCode();
        String operationType = data.getOperationType();

        // Check condition
        if (data.getCondition() != null && !data.getCondition().isBlank()) {
            Boolean condResult = evaluateCondition(data.getCondition(), processVars);
            if (!Boolean.TRUE.equals(condResult)) {
                log.info("Skipping serviceTask {} (condition false)", nodeId);
                processVars.put("_step_" + nodeId + "_skipped", true);
                stepResults.put(nodeId, Map.of("skipped", true));
                return;
            }
        }

        // Resolve params
        Map<String, Object> resolvedParams = resolveExpressions(data.getParams(), processVars);

        // Resolve targetRecordId
        String targetRecordId = null;
        if (data.getTargetRecordId() != null && !data.getTargetRecordId().isBlank()) {
            Object resolved = resolveExpression(data.getTargetRecordId(), processVars);
            targetRecordId = resolved != null ? resolved.toString() : null;
        }

        executionLogService.logNodeStart(chainExecPid, nodeId, "serviceTask",
                Map.of("commandCode", commandCode, "operationType", String.valueOf(operationType)));

        long startTime = System.currentTimeMillis();

        // Execute command
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setOperationType(operationType);
        request.setPayload(resolvedParams);
        request.setTargetRecordId(targetRecordId);

        CommandExecuteResult result = commandExecutor.execute(commandCode, request);

        long durationMs = System.currentTimeMillis() - startTime;

        // Write results back
        if (result.getData() != null) {
            processVars.put("_step_" + nodeId + "_result", result.getData());
            if (result.getData().containsKey("recordId")) {
                processVars.put("_step_" + nodeId + "_recordId", result.getData().get("recordId"));
            }
        }
        processVars.put("_step_" + nodeId + "_success", true);

        stepResults.put(nodeId, Map.of(
                "success", true,
                "commandCode", commandCode,
                "data", result.getData() != null ? result.getData() : Map.of()));

        executionLogService.logNodeComplete(chainExecPid, nodeId,
                Map.of("commandCode", commandCode, "success", true), durationMs);

        log.info("ServiceTask {} ({}) completed in {}ms", nodeId, commandCode, durationMs);
    }

    private String evaluateExclusiveGateway(String gatewayNodeId, CommandChainDefinition chain,
                                             Map<String, Object> processVars) {
        List<ChainEdge> outgoing = chain.getEdges().stream()
                .filter(e -> e.getSource().equals(gatewayNodeId))
                .toList();

        String defaultTarget = null;

        for (ChainEdge edge : outgoing) {
            if (edge.getCondition() == null || edge.getCondition().getContent() == null
                    || edge.getCondition().getContent().isBlank()) {
                defaultTarget = edge.getTarget();
                continue;
            }
            Boolean result = evaluateCondition(edge.getCondition().getContent(), processVars);
            if (Boolean.TRUE.equals(result)) {
                log.info("Gateway {} → taking branch to {} (condition: {})",
                        gatewayNodeId, edge.getTarget(), edge.getCondition().getContent());
                return edge.getTarget();
            }
        }

        if (defaultTarget != null) {
            log.info("Gateway {} → taking default branch to {}", gatewayNodeId, defaultTarget);
            return defaultTarget;
        }

        throw new IllegalStateException("No matching branch for exclusive gateway: " + gatewayNodeId);
    }

    private CommandChainResult handleRejection(String chainExecPid, ApprovalTask task,
                                                Map<String, Object> processVars,
                                                Map<String, Object> stepResults, String comment) {
        // Restore chain definition to check onReject config
        ChainExecution exec = chainExecutionMapper.selectOne(
                new QueryWrapper<ChainExecution>().eq("pid", chainExecPid));
        CommandChainDefinition chain = restoreChainDefinition(exec);
        ChainNode userTaskNode = findNodeById(task.getChainNodeId(), chain);

        // Execute rejection callback if configured
        if (userTaskNode != null && userTaskNode.getData() != null
                && userTaskNode.getData().getOnReject() != null) {
            Map<String, Object> onReject = userTaskNode.getData().getOnReject();
            try {
                String cmdCode = (String) onReject.get("commandCode");
                String opType = (String) onReject.get("operationType");
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) onReject.getOrDefault("params", Map.of());

                // Add rejection context to vars for template resolution
                processVars.put("_approval_comment", comment);
                Map<String, Object> resolvedParams = resolveExpressions(params, processVars);

                String targetId = (String) onReject.get("targetRecordId");
                if (targetId != null) {
                    Object resolved = resolveExpression(targetId, processVars);
                    targetId = resolved != null ? resolved.toString() : null;
                }

                CommandExecuteRequest request = new CommandExecuteRequest();
                request.setOperationType(opType);
                request.setPayload(resolvedParams);
                request.setTargetRecordId(targetId);
                commandExecutor.execute(cmdCode, request);

                log.info("Rejection callback {} executed for chain {}", cmdCode, chainExecPid);
            } catch (Exception e) {
                log.warn("Rejection callback failed for chain {}: {}", chainExecPid, e.getMessage());
            }
        }

        return failChain(chainExecPid,
                "Rejected by user " + task.getActualApproverId() + " at node " + task.getChainNodeId(),
                processVars, stepResults);
    }

    private CommandChainResult completeChain(String chainExecPid, Map<String, Object> processVars,
                                              Map<String, Object> stepResults, CommandChainDefinition chain) {
        chainExecutionMapper.update(null,
                new UpdateWrapper<ChainExecution>()
                        .set("status", StatusConstants.COMPLETED)
                        .set("process_variables", toJsonString(processVars))
                        .set("step_results", toJsonString(stepResults))
                        .set("completed_at", Instant.now())
                        .set("updated_at", Instant.now())
                        .eq("pid", chainExecPid));

        executionLogService.logStateChange(chainExecPid, "running", "completed", "Chain completed successfully");

        log.info("Chain {} completed successfully", chainExecPid);

        return CommandChainResult.builder()
                .success(true)
                .chainExecutionId(chainExecPid)
                .chainExecutionPid(chainExecPid)
                .processKey(chain.getProcessKey())
                .chainMode(ChainMode.APPROVAL)
                .status(StatusConstants.COMPLETED)
                .stepResults(stepResults)
                .build();
    }

    private CommandChainResult failChain(String chainExecPid, String errorMessage,
                                          Map<String, Object> processVars, Map<String, Object> stepResults) {
        chainExecutionMapper.update(null,
                new UpdateWrapper<ChainExecution>()
                        .set("status", StatusConstants.FAILED)
                        .set("error_message", errorMessage)
                        .set("process_variables", toJsonString(processVars))
                        .set("step_results", toJsonString(stepResults))
                        .set("completed_at", Instant.now())
                        .set("updated_at", Instant.now())
                        .eq("pid", chainExecPid));

        log.error("Chain {} failed: {}", chainExecPid, errorMessage);

        return CommandChainResult.builder()
                .success(false)
                .chainExecutionId(chainExecPid)
                .chainExecutionPid(chainExecPid)
                .chainMode(ChainMode.APPROVAL)
                .status(StatusConstants.FAILED)
                .errorMessage(errorMessage)
                .stepResults(stepResults)
                .build();
    }

    // ==================== Graph traversal helpers ====================

    private String findStartEventId(CommandChainDefinition chain) {
        return chain.getNodes().stream()
                .filter(n -> "startEvent".equals(n.getType()))
                .findFirst()
                .map(ChainNode::getId)
                .orElseThrow(() -> new IllegalStateException("No startEvent found in chain"));
    }

    private String findNextNodeId(String currentNodeId, CommandChainDefinition chain) {
        return chain.getEdges().stream()
                .filter(e -> e.getSource().equals(currentNodeId))
                .findFirst()
                .map(ChainEdge::getTarget)
                .orElse(null);
    }

    private ChainNode findNodeById(String nodeId, CommandChainDefinition chain) {
        return chain.getNodes().stream()
                .filter(n -> n.getId().equals(nodeId))
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private CommandChainDefinition restoreChainDefinition(ChainExecution exec) {
        return objectMapper.convertValue(exec.getChainDefinition(), CommandChainDefinition.class);
    }

    private Map<String, Object> buildFormSnapshot(Map<String, Object> processVars) {
        // Snapshot key business fields (exclude internal chain metadata)
        Map<String, Object> snapshot = new HashMap<>();
        for (Map.Entry<String, Object> entry : processVars.entrySet()) {
            if (!entry.getKey().startsWith("_chain_") && !entry.getKey().startsWith("_step_")
                    && !entry.getKey().startsWith("_approval_")) {
                snapshot.put(entry.getKey(), entry.getValue());
            }
        }
        return snapshot;
    }

    // ==================== SpEL helpers (same pattern as CommandServiceTaskDelegate) ====================

    private Object resolveExpression(String expression, Map<String, Object> processVars) {
        try {
            EvaluationContext context = SimpleEvaluationContext
                    .forPropertyAccessors(new org.springframework.context.expression.MapAccessor())
                    .withRootObject(processVars)
                    .build();
            for (Map.Entry<String, Object> entry : processVars.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    context.setVariable(entry.getKey(), entry.getValue());
                }
            }
            return spelParser.parseExpression(expression).getValue(context);
        } catch (Exception e) {
            log.warn("Failed to resolve expression '{}': {}", expression, e.getMessage());
            return null;
        }
    }

    private Boolean evaluateCondition(String expression, Map<String, Object> processVars) {
        try {
            Object result = resolveExpression(expression, processVars);
            if (result instanceof Boolean b) return b;
            return result != null;
        } catch (Exception e) {
            log.warn("Failed to evaluate condition '{}': {}", expression, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveExpressions(Map<String, Object> template,
                                                    Map<String, Object> processVars) {
        if (template == null || template.isEmpty()) return new HashMap<>();
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : template.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String strValue && strValue.startsWith("${") && strValue.endsWith("}")) {
                resolved.put(entry.getKey(), resolveExpression(strValue.substring(2, strValue.length() - 1), processVars));
            } else if (value instanceof Map) {
                resolved.put(entry.getKey(), resolveExpressions((Map<String, Object>) value, processVars));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    private String resolveTemplateString(String template, Map<String, Object> vars) {
        if (template == null) return null;
        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            Object value = vars.get(varName);
            if (value == null) {
                value = resolveExpression(varName, vars);
            }
            matcher.appendReplacement(sb, value != null ? Matcher.quoteReplacement(value.toString()) : "");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String toJsonString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Failed to serialize to JSON: {}", e.getMessage());
            return "{}";
        }
    }
}
