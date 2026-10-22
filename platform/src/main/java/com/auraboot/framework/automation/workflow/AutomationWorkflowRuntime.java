package com.auraboot.framework.automation.workflow;

import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.AutomationLog.ActionResult;
import com.auraboot.framework.plugin.extension.WorkflowCapability;
import com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Platform adapter for automation flows executed by an installed workflow product. */
@Service
@RequiredArgsConstructor
public class AutomationWorkflowRuntime {
    private final AutomationFlowCompiler compiler;
    private final WorkflowCapabilityRegistry workflowCapabilities;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Qualifier("compositeActionExecutor")
    private final org.springframework.beans.factory.ObjectProvider<com.auraboot.framework.automation.executor.ActionExecutor> actionExecutors;

    public String deploy(Automation automation) {
        AutomationFlowCompiler.CompiledFlow compiled = compiler.compile(automation);
        assertCompiledCapabilities(compiled);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("automationPid", automation.getPid());
        payload.put("processKey", compiled.processKey());
        payload.put("processName", automation.getName());
        payload.put("designerJson", objectMapper.convertValue(compiled.designerJson(), new TypeReference<Map<String, Object>>() {}));
        Map<String, Object> result = execute("automation.deploy", payload);
        return String.valueOf(result.getOrDefault("processKey", compiled.processKey()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ActionResult> run(Automation automation, String recordPid, Map<String, Object> triggerPayload) {
        return run(automation, recordPid, triggerPayload, null);
    }

    // Failed action transactions must roll back without erasing the enclosing automation log.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ActionResult> run(Automation automation, String recordPid,
                                  Map<String, Object> triggerPayload, Long automationLogId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("automationPid", automation.getPid());
        payload.put("processKey", "auto_" + automation.getPid());
        payload.put("actionsByNodeId", compiledActions(automation));
        if (automation.getModelCode() != null) payload.put("modelCode", automation.getModelCode());
        if (automation.getTenantId() != null) payload.put("automationTenantId", automation.getTenantId());
        if (automation.getCreatedBy() != null) payload.put("automationCreatedBy", automation.getCreatedBy());
        payload.put("automationActor", trustedActor(automation));
        if (recordPid != null) payload.put("recordPid", recordPid);
        payload.put("triggerPayload", triggerPayload == null ? Map.of() : triggerPayload);
        if (automationLogId != null) payload.put("automationLogId", automationLogId);
        try {
            Object rows = execute("automation.run", payload).get("actionResults");
            return rows == null ? List.of() : objectMapper.convertValue(rows, new TypeReference<List<ActionResult>>() {});
        } catch (WorkflowCapability.WorkflowExecutionException e) {
            Object rows = e.payload().get("actionResults");
            List<ActionResult> partialResults = rows == null
                    ? List.of()
                    : objectMapper.convertValue(rows, new TypeReference<List<ActionResult>>() {});
            throw new AutomationWorkflowRunException(
                    "Workflow product failed to run automation " + automation.getPid(), e, partialResults);
        } catch (RuntimeException e) {
            throw new AutomationWorkflowRunException(
                    "Workflow product failed to run automation " + automation.getPid(), e, List.of());
        }
    }

    private Map<String, Object> trustedActor(Automation automation) {
        if (!MetaContext.exists()) {
            throw new IllegalStateException("Automation execution requires an authenticated actor context");
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        Long memberId = MetaContext.getCurrentMemberId();
        String userPid = MetaContext.getCurrentUserPid();
        if (tenantId == null || !tenantId.equals(automation.getTenantId())
                || userId == null || userId <= 0 || memberId == null || memberId <= 0
                || userPid == null || userPid.isBlank()) {
            throw new IllegalStateException("Automation execution requires a valid actor in its owning tenant");
        }
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("tenantId", tenantId);
        actor.put("userId", userId);
        actor.put("userPid", userPid);
        actor.put("memberId", memberId);
        if (MetaContext.getCurrentUsername() != null) actor.put("username", MetaContext.getCurrentUsername());
        return actor;
    }

    private Map<String, Object> execute(String operation, Map<String, Object> payload) {
        return workflowCapabilities.execute(operation,
                new WorkflowCapability.WorkflowRequest(
                        MetaContext.exists() ? MetaContext.getCurrentTenantId() : null,
                        MetaContext.exists() ? MetaContext.getCurrentUserId() : null,
                        payload)).payload();
    }

    /** Reject unavailable LLM nodes before the trigger log or workflow product can perform work. */
    public void assertCapabilities(Automation automation) {
        if (!actionExecutors.getObject().supports("llm_call")) {
            assertCompiledCapabilities(compiler.compile(automation));
        }
    }

    private void assertCompiledCapabilities(AutomationFlowCompiler.CompiledFlow compiled) {
        boolean requiresLlm = compiled.actionsByNodeId().values().stream()
                .filter(value -> value instanceof Map<?, ?>)
                .map(value -> (Map<?, ?>) value)
                .anyMatch(action -> "llm_call".equals(action.get("type")));
        if (requiresLlm && !actionExecutors.getObject().supports("llm_call")) {
            throw new com.auraboot.framework.exception.BusinessException(
                    com.auraboot.framework.common.constant.ResponseCode.UnsupportedFeature,
                    "LLM workflow capability is not installed");
        }
    }

    private Map<String, Object> compiledActions(Automation automation) {
        var compiled = compiler.compile(automation);
        assertCompiledCapabilities(compiled);
        return compiled.actionsByNodeId();
    }

    public static class AutomationWorkflowRunException extends RuntimeException {
        private final List<ActionResult> actionResults;
        public AutomationWorkflowRunException(String message, Throwable cause, List<ActionResult> actionResults) {
            super(message, cause);
            this.actionResults = actionResults == null ? List.of() : List.copyOf(actionResults);
        }
        public List<ActionResult> getActionResults() { return actionResults; }
    }
}
