package com.auraboot.framework.automation.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.smart.framework.engine.persister.custom.session.PersisterSession;
import com.auraboot.smart.framework.engine.storage.StorageModeHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Deploys an automation's compiled flow to SmartEngine and runs it in MEMORY
 * (CUSTOM) storage mode (T2 slice 1c).
 *
 * <p>Deploy is idempotent: the first time creates + deploys a process definition
 * keyed {@code auto_<automationPid>}; subsequent calls deploy a new version.
 * Run forces CUSTOM storage so high-frequency automation runs do not persist
 * process instances to the database.
 */
@Slf4j
@Service
public class AutomationProcessRuntime {

    private final AutomationFlowCompiler compiler;
    private final ProcessDeploymentService deploymentService;
    private final ProcessEngineService processEngineService;
    private final ObjectMapper objectMapper;

    public AutomationProcessRuntime(AutomationFlowCompiler compiler,
                                    ProcessDeploymentService deploymentService,
                                    ProcessEngineService processEngineService,
                                    ObjectMapper objectMapper) {
        this.compiler = compiler;
        this.deploymentService = deploymentService;
        this.processEngineService = processEngineService;
        this.objectMapper = objectMapper;
    }

    /**
     * Compile + deploy the automation's flow to the SmartEngine repository.
     *
     * @return the deployed process key ({@code auto_<automationPid>})
     */
    public String deploy(Automation automation) {
        AutomationFlowCompiler.CompiledFlow compiled = compiler.compile(automation);
        String designerJson = serialize(compiled, automation);
        String processKey = compiled.processKey();

        BpmProcessDefinition existing = deploymentService.getByProcessKey(processKey);
        BpmProcessDefinition target = (existing == null)
                ? deploymentService.create(new ProcessDeploymentService.CreateProcessRequest(
                        processKey,
                        automation.getName() != null ? automation.getName() : processKey,
                        "automation:" + automation.getPid(),
                        "automation",
                        null,
                        designerJson,
                        null,
                        null))
                : deploymentService.createNewVersion(processKey, null, designerJson);

        deploymentService.deploy(target.getPid());
        log.info("Deployed automation process: key={}, defPid={}", processKey, target.getPid());
        return processKey;
    }

    /**
     * Run the automation flow on SmartEngine in MEMORY (CUSTOM) storage mode. The
     * per-node action specs travel via the {@code _automation_actions} process
     * variable consumed by {@link AutomationActionServiceTaskDelegate}.
     */
    public void run(Automation automation, String recordId, Map<String, Object> triggerPayload) {
        run(automation, recordId, triggerPayload, null);
    }

    /**
     * Run overload that also threads an {@code automationLogId} through as a process
     * variable. The {@link AutomationActionServiceTaskDelegate} uses it to insert
     * per-node execution rows into {@code ab_automation_node_execution} that link
     * back to the parent {@code ab_automation_log} row (G5 runtime overlay).
     *
     * <p>Passing {@code null} disables per-node recording — useful for synthetic /
     * test contexts that have no log row.
     */
    public void run(Automation automation, String recordId,
                    Map<String, Object> triggerPayload, Long automationLogId) {
        AutomationFlowCompiler.CompiledFlow compiled = compiler.compile(automation);

        Map<String, Object> variables = new HashMap<>();
        if (triggerPayload != null) {
            variables.putAll(triggerPayload);
        }
        if (recordId != null) {
            variables.put("recordId", recordId);
        }
        variables.put(AutomationActionServiceTaskDelegate.ACTIONS_VAR, compiled.actionsByNodeId());
        if (automationLogId != null) {
            variables.put(AutomationActionServiceTaskDelegate.LOG_ID_VAR, automationLogId);
        }
        variables.put(AutomationActionServiceTaskDelegate.AUTOMATION_ID_VAR, automation.getPid());
        if (automation.getTenantId() != null) {
            variables.put(AutomationActionServiceTaskDelegate.TENANT_ID_VAR, automation.getTenantId());
        }

        // SmartEngine process variables cannot be null — a null value NPEs deep in
        // startProcess ("Cannot invoke Object.getClass() because value is null"). Trigger
        // payloads legitimately carry nulls (e.g. on_state_change fromState when no
        // before-snapshot was captured, on_field_change oldValue on a first set), so drop
        // null-valued entries: an absent variable is the correct semantics for a null.
        // Without this, every such automation run crashed. (Golden FINDING-4.)
        variables.values().removeIf(java.util.Objects::isNull);

        // The trigger path runs on @Async("eventTaskExecutor") threads that may not carry
        // a MetaContext; startProcess needs the tenant. Set it from the automation when absent.
        boolean tenantContextSet = false;
        if (!MetaContext.exists() && automation.getTenantId() != null) {
            MetaContext.setContext(automation.getTenantId(), null, automation.getCreatedBy(), null);
            tenantContextSet = true;
        }

        PersisterSession.create();
        StorageModeHolder.set(com.auraboot.smart.framework.engine.storage.StorageMode.CUSTOM);
        try {
            processEngineService.startProcess(compiled.processKey(), recordId, variables);
        } finally {
            StorageModeHolder.clear();
            PersisterSession.destroySession();
            if (tenantContextSet) {
                MetaContext.clear();
            }
        }
    }

    private String serialize(AutomationFlowCompiler.CompiledFlow compiled, Automation automation) {
        try {
            return objectMapper.writeValueAsString(compiled.designerJson());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failed to serialize compiled flow for automation " + automation.getPid(), e);
        }
    }
}
