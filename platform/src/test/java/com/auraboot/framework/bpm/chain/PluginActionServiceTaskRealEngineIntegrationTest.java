package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.extension.ServiceTaskActionExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-engine integration test for F3: a plugin {@link ServiceTaskActionExtension} is invoked from a
 * deployed BPMN through the host {@code pluginActionServiceTaskDelegate}.
 *
 * <p>Proves the full wiring with a real SmartEngine (not mocks): SmartEngine resolves the
 * serviceTask's {@code smart:class="pluginActionServiceTaskDelegate"} bean, the delegate reads
 * {@code smart:action}, {@link ExtensionRegistry#getServiceTaskAction(String)} resolves the
 * registered extension, and {@link ServiceTaskActionExtension#execute} runs — receiving the live
 * process variables and serviceTask properties.
 *
 * <p>The extension is registered as a core Spring bean via the nested {@link F3TestConfig}; the
 * registry discovers it through the same {@code getExtensionsOfType}/core-provider merge path used
 * for the (already plugin-proven) command handlers, so the PF4J classloader path is covered by the
 * same infrastructure.
 */
@Import(PluginActionServiceTaskRealEngineIntegrationTest.F3TestConfig.class)
@DisplayName("PluginActionServiceTaskDelegate invokes a ServiceTaskActionExtension from a deployed BPMN (F3, real SmartEngine)")
class PluginActionServiceTaskRealEngineIntegrationTest extends BaseIntegrationTest {

    static final String ACTION_TYPE = "it_f3:capture";

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private ProcessEngineService processEngineService;

    @Autowired
    private ExtensionRegistry extensionRegistry;

    /** start → plugin_action (pluginActionServiceTaskDelegate) → end. */
    private static final String BPMN_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:smart="http://smartengine.org/schema/process"
                              targetNamespace="http://auraboot.com/bpm"
                              id="%1$s-defs">
              <bpmn:process id="%1$s" name="F3 plugin action" isExecutable="true">
                <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="plugin_action"/>
                <bpmn:serviceTask id="plugin_action" name="Run plugin action"
                                  smart:class="pluginActionServiceTaskDelegate"
                                  smart:action="it_f3:capture"
                                  smart:resultVar="captureResult"
                                  smart:note="hello">
                  <bpmn:incoming>f1</bpmn:incoming>
                  <bpmn:outgoing>f2</bpmn:outgoing>
                </bpmn:serviceTask>
                <bpmn:sequenceFlow id="f2" sourceRef="plugin_action" targetRef="end"/>
                <bpmn:endEvent id="end"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
              </bpmn:process>
            </bpmn:definitions>
            """;

    @BeforeEach
    void resetCaptures() {
        F3TestConfig.INVOCATIONS.clear();
        // The registry caches extensions lazily; force a reload so the test bean is visible.
        extensionRegistry.refreshAllCaches();
    }

    @Test
    @DisplayName("a registered ServiceTaskActionExtension is invoked with process vars + serviceTask properties")
    void deployedBpmn_invokesPluginActionExtension() {
        // Sanity: the registry resolves the test-registered extension.
        assertThat(extensionRegistry.getServiceTaskAction(ACTION_TYPE))
                .as("ServiceTaskActionExtension for %s must be registered", ACTION_TYPE)
                .isPresent();

        String processKey = "it-f3-action-" + System.nanoTime();
        ProcessDeploymentService.CreateProcessRequest request =
                new ProcessDeploymentService.CreateProcessRequest(
                        processKey, "F3 plugin action", "F3 IT",
                        "test", String.format(BPMN_TEMPLATE, processKey),
                        null, null, null);
        BpmProcessDefinition def = deploymentService.create(request);
        deploymentService.deploy(def.getPid());

        Map<String, Object> startVars = new HashMap<>();
        startVars.put("deviceId", "dev-42");
        ProcessInstance instance = processEngineService.startProcess(processKey, "biz-" + System.nanoTime(), startVars);
        assertThat(instance).as("process instance created").isNotNull();

        // The serviceTask ran the extension exactly once, with the live process vars and the
        // serviceTask's smart:* properties.
        assertThat(F3TestConfig.INVOCATIONS).as("extension invoked exactly once").hasSize(1);
        ServiceTaskActionExtension.ActionContext ctx = F3TestConfig.INVOCATIONS.get(0);
        assertThat(ctx.actionType()).isEqualTo(ACTION_TYPE);
        assertThat(ctx.variables()).containsEntry("deviceId", "dev-42");
        assertThat(ctx.properties())
                .containsEntry(BpmServiceTaskConstants.ATTR_ACTION, ACTION_TYPE)
                .containsEntry("note", "hello");
    }

    @TestConfiguration
    static class F3TestConfig {
        static final List<ServiceTaskActionExtension.ActionContext> INVOCATIONS = new CopyOnWriteArrayList<>();

        @Bean
        ServiceTaskActionExtension capturingTestAction() {
            return new ServiceTaskActionExtension() {
                @Override
                public String getActionType() {
                    return ACTION_TYPE;
                }

                @Override
                public Object execute(ActionContext context) {
                    INVOCATIONS.add(context);
                    return Map.of("captured", true, "device", context.variables().get("deviceId"));
                }
            };
        }
    }
}
