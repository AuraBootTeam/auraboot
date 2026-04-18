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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Call-activity follow-up: nested parent → child → grandchild propagation.
 *
 * <p>{@link BpmCallActivityVarsTest} verifies the single-hop case (parent ↔ child).
 * Real-world workflows compose 3 levels (e.g. Order → Approval → Credit-Check);
 * this test locks in that {@code aura.callMappings} composes correctly across
 * two hops: a value supplied at the parent is visible at the grandchild, and
 * a value produced at the grandchild surfaces back at the parent.
 */
@Slf4j
@DisplayName("BPM CallActivity Nested (parent → child → grandchild)")
class BpmCallActivityNestedTest extends BaseIntegrationTest {

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private ProcessEngineService processEngineService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private JsonToBpmnConverter jsonToBpmnConverter;

    // ---------------------------------------------------------------------
    // Grandchild: simplest 3-node process with a single userTask that the
    // starter user has to complete. Terminates the call chain.
    // ---------------------------------------------------------------------
    private static final String GRANDCHILD_BPMN_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:smart="http://smart.alibaba.com"
                         targetNamespace="http://auraboot.com/bpm">
              <process id="%s" name="CallActivity Grandchild" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="gc_task"/>
                <userTask id="gc_task" name="Grandchild Review"
                          smart:assigneeType="starter"/>
                <sequenceFlow id="f2" sourceRef="gc_task" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """;

    private BpmProcessDefinition deployGrandchild(String suffix) {
        String key = "ca-gc-" + suffix + "-" + System.nanoTime();
        String bpmn = GRANDCHILD_BPMN_TEMPLATE.formatted(key);
        BpmProcessDefinition def = deploymentService.create(
                new ProcessDeploymentService.CreateProcessRequest(
                        key, "CA Grandchild " + suffix, "grandchild", "test",
                        bpmn, null, null, null));
        deploymentService.deploy(def.getPid());
        return def;
    }

    /**
     * Child designer JSON: invokes the grandchild via callActivity with input
     * mapping {@code childInput → grandchildInput} and output mapping
     * {@code grandchildOutput → childOutput}. When the child subsequently
     * completes back to the parent, the parent's output mapping
     * {@code childOutput → parentOutput} carries the end-to-end propagation.
     */
    private String buildChildDesignerJson(String childKey, String grandchildKey) {
        return """
                {
                  "key": "%s",
                  "name": "CallActivity Child (nested)",
                  "nodes": [
                    {"id":"start","type":"startEvent","position":{"x":0,"y":0},
                     "data":{"type":"startEvent","label":"Start"}},
                    {"id":"invoke_gc","type":"callActivity","position":{"x":200,"y":0},
                     "data":{"type":"callActivity","label":"Invoke Grandchild","config":{
                       "calledProcessKey":"%s",
                       "calledProcessVersion":"1.0.0",
                       "inputMappings":{"childInput":"grandchildInput"},
                       "outputMappings":{"grandchildOutput":"childOutput"}
                     }}},
                    {"id":"end","type":"endEvent","position":{"x":400,"y":0},
                     "data":{"type":"endEvent","label":"End"}}
                  ],
                  "edges":[
                    {"id":"e1","source":"start","target":"invoke_gc","data":{}},
                    {"id":"e2","source":"invoke_gc","target":"end","data":{}}
                  ]
                }
                """.formatted(childKey, grandchildKey);
    }

    private String buildParentDesignerJson(String parentKey, String childKey) {
        return """
                {
                  "key": "%s",
                  "name": "CallActivity Parent (nested)",
                  "nodes": [
                    {"id":"start","type":"startEvent","position":{"x":0,"y":0},
                     "data":{"type":"startEvent","label":"Start"}},
                    {"id":"invoke_child","type":"callActivity","position":{"x":200,"y":0},
                     "data":{"type":"callActivity","label":"Invoke Child","config":{
                       "calledProcessKey":"%s",
                       "calledProcessVersion":"1.0.0",
                       "inputMappings":{"parentInput":"childInput"},
                       "outputMappings":{"childOutput":"parentOutput"}
                     }}},
                    {"id":"end","type":"endEvent","position":{"x":400,"y":0},
                     "data":{"type":"endEvent","label":"End"}}
                  ],
                  "edges":[
                    {"id":"e1","source":"start","target":"invoke_child","data":{}},
                    {"id":"e2","source":"invoke_child","target":"end","data":{}}
                  ]
                }
                """.formatted(parentKey, childKey);
    }

    private BpmProcessDefinition deployFromDesigner(String processKey, String designerJson,
                                                    String name) {
        String bpmn = jsonToBpmnConverter.convert(designerJson);
        BpmProcessDefinition def = deploymentService.create(
                new ProcessDeploymentService.CreateProcessRequest(
                        processKey, name, "nested CA fixture", "test",
                        bpmn, designerJson, null, null));
        deploymentService.deploy(def.getPid());
        return def;
    }

    // =====================================================================
    // NESTED-01: end-to-end variable propagation across 2 callActivity hops
    // =====================================================================
    @Test
    @DisplayName("NESTED-01: parent → child → grandchild; input & output propagate across both hops")
    void nested_fullPropagation() {
        // Deploy from leaf to root
        BpmProcessDefinition grandchild = deployGrandchild("n01");

        String childKey = "ca-child-n01-" + System.nanoTime();
        deployFromDesigner(childKey,
                buildChildDesignerJson(childKey, grandchild.getProcessKey()),
                "CA Child n01");

        String parentKey = "ca-parent-n01-" + System.nanoTime();
        BpmProcessDefinition parent = deployFromDesigner(parentKey,
                buildParentDesignerJson(parentKey, childKey),
                "CA Parent n01");

        String inputValue = "in-" + System.nanoTime();
        String outputValue = "out-" + System.nanoTime();

        // Start the parent
        Map<String, Object> startVars = new HashMap<>();
        startVars.put("_startUserId", MetaContext.getCurrentUserId() + "");
        startVars.put("parentInput", inputValue);
        ProcessInstance parentInstance = processEngineService.startProcess(
                parent.getProcessKey(),
                "CA-NEST-" + System.nanoTime(),
                startVars);

        // The only pending task in the entire chain should be the grandchild's.
        TaskInstance gcTask = findTodoTask(grandchild.getProcessKey(), "gc_task");
        assertThat(gcTask)
                .as("grandchild userTask must materialize after two callActivity hops")
                .isNotNull();

        // Grandchild must see the original input, hop-propagated through child.
        ProcessInstanceStatusDTO gcStatus = processEngineService
                .getProcessInstanceStatus(gcTask.getProcessInstanceId());
        assertThat(gcStatus.variables())
                .as("end-to-end input propagation: parentInput → childInput → grandchildInput")
                .containsEntry("grandchildInput", inputValue);

        // Complete grandchild with an output value. The chain must reverse:
        // grandchildOutput → childOutput → parentOutput.
        Map<String, Object> completeVars = new HashMap<>();
        completeVars.put("grandchildOutput", outputValue);
        taskService.completeTask(gcTask.getInstanceId(), completeVars);

        ProcessInstanceStatusDTO parentStatus = processEngineService
                .getProcessInstanceStatus(parentInstance.getInstanceId());
        assertThat(parentStatus.status())
                .as("parent must complete once both nested call activities return")
                .isEqualTo("completed");
        assertThat(parentStatus.variables())
                .as("end-to-end output propagation across two hops")
                .containsEntry("parentOutput", outputValue);
    }

    // =====================================================================
    // NESTED-02: grandchild runs but produces no output → parent surfaces no
    // parentOutput (i.e. absent key, not null) — output mapping is a pull of
    // a variable that must exist at the callee, not an implicit default.
    // =====================================================================
    @Test
    @DisplayName("NESTED-02: grandchild completes without producing grandchildOutput → parent has no parentOutput")
    void nested_missingOutputDoesNotPollute() {
        BpmProcessDefinition grandchild = deployGrandchild("n02");

        String childKey = "ca-child-n02-" + System.nanoTime();
        deployFromDesigner(childKey,
                buildChildDesignerJson(childKey, grandchild.getProcessKey()),
                "CA Child n02");

        String parentKey = "ca-parent-n02-" + System.nanoTime();
        BpmProcessDefinition parent = deployFromDesigner(parentKey,
                buildParentDesignerJson(parentKey, childKey),
                "CA Parent n02");

        Map<String, Object> startVars = new HashMap<>();
        startVars.put("_startUserId", MetaContext.getCurrentUserId() + "");
        startVars.put("parentInput", "hello");
        ProcessInstance parentInstance = processEngineService.startProcess(
                parent.getProcessKey(),
                "CA-NEST-NOUT-" + System.nanoTime(),
                startVars);

        TaskInstance gcTask = findTodoTask(grandchild.getProcessKey(), "gc_task");
        assertThat(gcTask).isNotNull();

        // Complete with NO grandchildOutput variable.
        taskService.completeTask(gcTask.getInstanceId(), new HashMap<>());

        ProcessInstanceStatusDTO parentStatus = processEngineService
                .getProcessInstanceStatus(parentInstance.getInstanceId());
        assertThat(parentStatus.status())
                .as("parent still completes cleanly when grandchild produces no output")
                .isEqualTo("completed");
        // The absence of parentOutput is a positive assertion: output mapping
        // must NOT invent a null/empty placeholder.
        assertThat(parentStatus.variables())
                .as("missing upstream value must not pollute parent scope")
                .doesNotContainKey("parentOutput");
    }

    /**
     * Walk the current user's todo list (and the "system" fallback used by
     * BaseIntegrationTest when no security principal is seeded) looking for
     * a task on the given process key & activity id.
     */
    private TaskInstance findTodoTask(String processKey, String activityId) {
        for (String userId : new String[] {
                MetaContext.getCurrentUserId() + "", "system"
        }) {
            List<TaskInstance> todos = taskService.getTodoTasks(userId);
            for (TaskInstance t : todos) {
                String pd = t.getProcessDefinitionIdAndVersion();
                if (pd != null && pd.startsWith(processKey + ":")
                        && activityId.equals(t.getProcessDefinitionActivityId())) {
                    return t;
                }
            }
        }
        return null;
    }
}
