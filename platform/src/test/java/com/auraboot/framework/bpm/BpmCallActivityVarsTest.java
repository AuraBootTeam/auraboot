package com.auraboot.framework.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.converter.JsonToBpmnConverter;
import com.auraboot.framework.bpm.dto.ProcessInstanceStatusDTO;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.bpm.service.TaskService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the GAP-250 follow-up: {@code AuraCallActivityListener}
 * bridges SmartEngine's intentional parent↔child request-map isolation on
 * {@code <callActivity>} via the {@code aura.callMappings} smart:property.
 *
 * <p>What these tests guarantee end-to-end:
 * <ul>
 *   <li>Input mappings: starting the parent with {@code parentInput=X} seeds
 *       the child's request map with {@code childInput=X} at the child's
 *       {@code PROCESS_START}. The child's downstream userTask sees the mapped
 *       variable via SmartEngine's normal variable-instance persistence.</li>
 *   <li>Output mappings: completing the child's userTask with
 *       {@code childOutput=Y} surfaces {@code parentOutput=Y} on the parent's
 *       process-instance status after the parent completes. This is the
 *       {@code ACTIVITY_END} side of the bridge.</li>
 * </ul>
 *
 * <p>Covers CA-2/CA-3 runtime assertions from
 * {@code web-admin/tests/e2e/bpm/designer-callactivity.spec.ts}; the E2E
 * drives the UI path + BPMN round-trip, this file validates the engine-level
 * contract directly against {@code ProcessEngineService}.
 */
@Slf4j
@DisplayName("BPM CallActivity Parent↔Child Variable Propagation")
class BpmCallActivityVarsTest extends BaseIntegrationTest {

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private ProcessEngineService processEngineService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private JsonToBpmnConverter jsonToBpmnConverter;

    // ---------------------------------------------------------------------
    // Child BPMN: start → child_review (starter) → end
    // The userTask collects childOutput at completion; with assigneeType=starter
    // the platform routes it back to whichever user started the parent chain.
    // ---------------------------------------------------------------------
    private static final String CHILD_BPMN_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:smart="http://smart.alibaba.com"
                         targetNamespace="http://auraboot.com/bpm">
              <process id="%s" name="CallActivity Child" isExecutable="true">
                <startEvent id="start" name="Start"/>
                <sequenceFlow id="f_start_review" sourceRef="start" targetRef="child_review"/>
                <userTask id="child_review" name="Child Review"
                          smart:assigneeType="starter"/>
                <sequenceFlow id="f_review_end" sourceRef="child_review" targetRef="end"/>
                <endEvent id="end" name="End"/>
              </process>
            </definitions>
            """;

    /**
     * Build the parent designerJson that {@code JsonToBpmnConverter} will turn
     * into a BPMN document carrying an {@code aura.callMappings} smart:property
     * under the callActivity's extensionElements. Using the converter here is
     * deliberate — it is the code path the production deploy flow also takes
     * (see ProcessDeploymentService.deploy when bpmnContent is blank), so the
     * test locks in the full converter → parser → runtime chain.
     */
    private String buildParentDesignerJson(String parentKey, String childKey) {
        return """
                {
                  "key": "%s",
                  "name": "CallActivity Parent",
                  "nodes": [
                    {"id":"start","type":"startEvent","position":{"x":0,"y":0},"data":{"type":"startEvent","label":"Start"}},
                    {"id":"invoke_child","type":"callActivity","position":{"x":200,"y":0},"data":{"type":"callActivity","label":"Invoke Child","config":{
                      "calledProcessKey":"%s",
                      "calledProcessVersion":"1.0.0",
                      "inputMappings":{"parentInput":"childInput"},
                      "outputMappings":{"childOutput":"parentOutput"}
                    }}},
                    {"id":"end","type":"endEvent","position":{"x":400,"y":0},"data":{"type":"endEvent","label":"End"}}
                  ],
                  "edges": [
                    {"id":"f1","source":"start","target":"invoke_child","data":{}},
                    {"id":"f2","source":"invoke_child","target":"end","data":{}}
                  ]
                }
                """.formatted(parentKey, childKey);
    }

    private BpmProcessDefinition deployChild(String suffix) {
        String key = "ca-child-" + suffix + "-" + System.nanoTime();
        String bpmn = CHILD_BPMN_TEMPLATE.formatted(key);
        BpmProcessDefinition def = deploymentService.create(
                new ProcessDeploymentService.CreateProcessRequest(
                        key, "CA Child " + suffix, "child fixture", "test",
                        bpmn, null, null, null));
        deploymentService.deploy(def.getPid());
        return def;
    }

    private BpmProcessDefinition deployParent(String suffix, String childKey) {
        String key = "ca-parent-" + suffix + "-" + System.nanoTime();
        String designerJson = buildParentDesignerJson(key, childKey);
        // Run the converter directly rather than relying on deploy-time
        // auto-conversion so we have strict control over the XML carrying
        // aura.callMappings into SmartEngine.
        String bpmn = jsonToBpmnConverter.convert(designerJson);
        log.debug("Parent BPMN:\n{}", bpmn);
        BpmProcessDefinition def = deploymentService.create(
                new ProcessDeploymentService.CreateProcessRequest(
                        key, "CA Parent " + suffix,
                        "parent w/ callActivity mappings", "test",
                        bpmn, designerJson, null, null));
        deploymentService.deploy(def.getPid());
        return def;
    }

    // =========================================================================
    // CA-VARS-01: input + output mapping full round-trip
    // =========================================================================
    @Test
    @DisplayName("CA-VARS-01: parent → child input mapping + child → parent output mapping round-trip via aura.callMappings")
    void ca_vars_01_fullRoundTrip() {
        // Arrange: deploy both child and parent
        BpmProcessDefinition child = deployChild("v01");
        BpmProcessDefinition parent = deployParent("v01", child.getProcessKey());

        String inputValue = "hello-" + System.nanoTime();
        String outputValue = "world-" + System.nanoTime();

        // Act 1: start parent with parentInput=<inputValue>
        Map<String, Object> startVars = new HashMap<>();
        startVars.put("_startUserId", MetaContext.getCurrentUserId() + "");
        startVars.put("parentInput", inputValue);
        ProcessInstance parentInstance = processEngineService.startProcess(
                parent.getProcessKey(),
                "CA-VARS-" + System.nanoTime(),
                startVars);
        assertThat(parentInstance).isNotNull();

        // Assert 1: child instance exists and sees childInput=<inputValue>.
        // The child userTask (child_review) is the only pending task — its
        // host process instance is the child we just spawned.
        TaskInstance childTask = findChildReviewTask(parentInstance.getInstanceId(), child.getProcessKey());
        assertThat(childTask)
                .as("child_review userTask must be spawned on child instance")
                .isNotNull();
        String childInstanceId = childTask.getProcessInstanceId();
        assertThat(childInstanceId)
                .as("child instanceId must differ from parent")
                .isNotEqualTo(parentInstance.getInstanceId());

        ProcessInstanceStatusDTO childStatus =
                processEngineService.getProcessInstanceStatus(childInstanceId);
        assertThat(childStatus).isNotNull();
        assertThat(childStatus.variables())
                .as("input mapping: parentInput=%s must propagate as childInput", inputValue)
                .containsEntry("childInput", inputValue);

        // Act 2: complete child_review with childOutput=<outputValue>
        Map<String, Object> completeVars = new HashMap<>();
        completeVars.put("childOutput", outputValue);
        taskService.completeTask(childTask.getInstanceId(), completeVars);

        // Assert 2: parent receives parentOutput=<outputValue> via output mapping.
        // Parent completes once the callActivity's leave fires (ACTIVITY_END
        // is where output-mapping runs), so we read its final status directly.
        ProcessInstanceStatusDTO parentStatus =
                processEngineService.getProcessInstanceStatus(parentInstance.getInstanceId());
        assertThat(parentStatus).isNotNull();
        assertThat(parentStatus.status())
                .as("parent must complete after child callActivity returns")
                .isEqualTo("completed");
        assertThat(parentStatus.variables())
                .as("output mapping: child's childOutput=%s must surface as parentOutput", outputValue)
                .containsEntry("parentOutput", outputValue);
    }

    // =========================================================================
    // CA-VARS-02: listener is a no-op when aura.callMappings is absent.
    // Establishes that the propagation is entirely driven by the property —
    // a callActivity without the extension still deploys and runs fine, with
    // complete parent/child variable isolation preserved (regression guard
    // against accidentally making the listener unconditional).
    // =========================================================================
    @Test
    @DisplayName("CA-VARS-02: callActivity without aura.callMappings preserves SmartEngine isolation (no leak)")
    void ca_vars_02_noMappingsNoLeak() {
        BpmProcessDefinition child = deployChild("v02");

        String parentKey = "ca-parent-v02-" + System.nanoTime();
        String designerJsonNoMappings = """
                {
                  "key": "%s",
                  "name": "CA Parent NoMap",
                  "nodes": [
                    {"id":"start","type":"startEvent","position":{"x":0,"y":0},"data":{"type":"startEvent","label":"Start"}},
                    {"id":"invoke_child","type":"callActivity","position":{"x":200,"y":0},"data":{"type":"callActivity","label":"Invoke Child","config":{
                      "calledProcessKey":"%s",
                      "calledProcessVersion":"1.0.0"
                    }}},
                    {"id":"end","type":"endEvent","position":{"x":400,"y":0},"data":{"type":"endEvent","label":"End"}}
                  ],
                  "edges": [
                    {"id":"f1","source":"start","target":"invoke_child","data":{}},
                    {"id":"f2","source":"invoke_child","target":"end","data":{}}
                  ]
                }
                """.formatted(parentKey, child.getProcessKey());
        String bpmn = jsonToBpmnConverter.convert(designerJsonNoMappings);
        BpmProcessDefinition parent = deploymentService.create(
                new ProcessDeploymentService.CreateProcessRequest(
                        parentKey, "CA Parent NoMap", "no mappings", "test",
                        bpmn, designerJsonNoMappings, null, null));
        deploymentService.deploy(parent.getPid());

        Map<String, Object> startVars = new HashMap<>();
        startVars.put("_startUserId", MetaContext.getCurrentUserId() + "");
        startVars.put("parentInput", "should-not-propagate");
        ProcessInstance parentInstance = processEngineService.startProcess(
                parent.getProcessKey(),
                "CA-VARS-NOMAP-" + System.nanoTime(),
                startVars);

        TaskInstance childTask = findChildReviewTask(parentInstance.getInstanceId(), child.getProcessKey());
        assertThat(childTask)
                .as("child_review must still spawn even without mappings")
                .isNotNull();

        ProcessInstanceStatusDTO childStatus =
                processEngineService.getProcessInstanceStatus(childTask.getProcessInstanceId());
        assertThat(childStatus.variables())
                .as("without aura.callMappings, parent variables must NOT leak into child")
                .doesNotContainKey("childInput")
                .doesNotContainKey("parentInput");
    }

    /**
     * Locate the {@code child_review} userTask on the spawned child process
     * instance. SmartEngine does not expose a direct parent→child task lookup
     * (tasks carry {@code processInstanceId} of the child, not of the parent
     * that called the callActivity). We therefore read the current user's
     * todo-task list and match on
     * {@code processDefinitionIdAndVersion == childProcessKey:version}.
     */
    private TaskInstance findChildReviewTask(String parentInstanceId, String childProcessKey) {
        // Try the current MetaContext user first; fall back to the
        // BpmSecurityUtil default ("system") that ProcessEngineService uses
        // when no authenticated principal is present. In BaseIntegrationTest
        // the tasks are typically assigned to "system" since the test harness
        // does not seed a security principal.
        for (String userId : new String[] {
                MetaContext.getCurrentUserId() + "", "system"
        }) {
            List<TaskInstance> todos = taskService.getTodoTasks(userId);
            for (TaskInstance t : todos) {
                String pdIdVersion = t.getProcessDefinitionIdAndVersion();
                if (pdIdVersion != null && pdIdVersion.startsWith(childProcessKey + ":")
                        && "child_review".equals(t.getProcessDefinitionActivityId())) {
                    return t;
                }
            }
        }
        return null;
    }
}
