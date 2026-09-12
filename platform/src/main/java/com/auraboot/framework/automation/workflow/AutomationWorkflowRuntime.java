package com.auraboot.framework.automation.workflow;

import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationLog.ActionResult;
import com.auraboot.framework.plugin.extension.WorkflowCapability;
import com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

    public String deploy(Automation automation) {
        AutomationFlowCompiler.CompiledFlow compiled = compiler.compile(automation);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("automationPid", automation.getPid());
        payload.put("processKey", compiled.processKey());
        payload.put("processName", automation.getName());
        payload.put("designerJson", objectMapper.convertValue(compiled.designerJson(), new TypeReference<Map<String, Object>>() {}));
        Map<String, Object> result = execute("automation.deploy", payload);
        return String.valueOf(result.getOrDefault("processKey", compiled.processKey()));
    }

    public List<ActionResult> run(Automation automation, String recordPid, Map<String, Object> triggerPayload) {
        return run(automation, recordPid, triggerPayload, null);
    }

    public List<ActionResult> run(Automation automation, String recordPid,
                                  Map<String, Object> triggerPayload, Long automationLogId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("automationPid", automation.getPid());
        payload.put("processKey", "auto_" + automation.getPid());
        payload.put("actionsByNodeId", compiledActions(automation));
        if (automation.getModelCode() != null) payload.put("modelCode", automation.getModelCode());
        if (automation.getTenantId() != null) payload.put("automationTenantId", automation.getTenantId());
        if (automation.getCreatedBy() != null) payload.put("automationCreatedBy", automation.getCreatedBy());
        if (recordPid != null) payload.put("recordPid", recordPid);
        payload.put("triggerPayload", triggerPayload == null ? Map.of() : triggerPayload);
        if (automationLogId != null) payload.put("automationLogId", automationLogId);
        try {
            Object rows = execute("automation.run", payload).get("actionResults");
            return rows == null ? List.of() : objectMapper.convertValue(rows, new TypeReference<List<ActionResult>>() {});
        } catch (RuntimeException e) {
            throw new AutomationWorkflowRunException("Workflow product failed to run automation " + automation.getPid(), e, List.of());
        }
    }

    private Map<String, Object> execute(String operation, Map<String, Object> payload) {
        return workflowCapabilities.execute(operation,
                new WorkflowCapability.WorkflowRequest(null, null, payload)).payload();
    }

    private Map<String, Object> compiledActions(Automation automation) {
        return compiler.compile(automation).actionsByNodeId();
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
