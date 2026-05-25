package com.auraboot.framework.automation.bpm;

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
        AutomationFlowCompiler.CompiledFlow compiled = compiler.compile(automation);

        Map<String, Object> variables = new HashMap<>();
        if (triggerPayload != null) {
            variables.putAll(triggerPayload);
        }
        if (recordId != null) {
            variables.put("recordId", recordId);
        }
        variables.put(AutomationActionServiceTaskDelegate.ACTIONS_VAR, compiled.actionsByNodeId());

        PersisterSession.create();
        StorageModeHolder.set(com.auraboot.smart.framework.engine.storage.StorageMode.CUSTOM);
        try {
            processEngineService.startProcess(compiled.processKey(), recordId, variables);
        } finally {
            StorageModeHolder.clear();
            PersisterSession.destroySession();
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
