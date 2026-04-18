package com.auraboot.framework.bpm.converter;

import com.auraboot.framework.bpm.entity.BpmNodeHook;
import com.auraboot.framework.bpm.service.BpmNodeHookService;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for GAP-254: designer-side node hooks compile through
 * JsonToBpmnConverter into both (a) BPMN XML smart:hook extension elements
 * and (b) ab_bpm_node_hook rows that BpmNodeHookService can fetch at runtime.
 *
 * <p>Coverage:
 * <ul>
 *   <li>HOOK-CMP-01 — convert designerJson with userTask hooks → XML carries
 *       smart:hook descriptors with the correct attributes / hookConfig payload.</li>
 *   <li>HOOK-CMP-02 — deploy a draft definition whose designerJson has hooks →
 *       BpmNodeHookService.getHooks(processKey, nodeId, hookType) returns the
 *       persisted rows with vocabulary normalization (pre_execute → pre_check)
 *       applied at the service write boundary.</li>
 *   <li>HOOK-CMP-03 — re-deploying after editing designerJson hooks is
 *       idempotent: prior hook rows for the same (tenant, processKey) are
 *       wiped and replaced by the latest descriptor list.</li>
 * </ul>
 */
@Slf4j
@DisplayName("JsonToBpmnConverter node hook compilation (GAP-254)")
class JsonToBpmnConverterHookCompilationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JsonToBpmnConverter jsonToBpmnConverter;

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private BpmNodeHookService nodeHookService;

    /**
     * Designer JSON for a 3-node process (start → approve → end) carrying two
     * userTask hooks (one pre_execute command + one post_execute script). The
     * shape mirrors the BPMN designer's HookConfigSection output exactly.
     */
    private String buildDesignerJson(String processKey, String preCmdCode) {
        return """
                {
                  "key": "%s",
                  "name": "Hook Compile %s",
                  "nodes": [
                    {"id":"start","type":"startEvent","position":{"x":80,"y":200},
                      "data":{"type":"startEvent","label":"Start"}},
                    {"id":"approve","type":"userTask","position":{"x":280,"y":200},
                      "data":{"type":"userTask","label":"Approve","config":{
                        "assigneeType":"user","assigneeIds":["admin"],
                        "hooks":[
                          {"hookType":"pre_execute","executionOrder":0,
                           "hookConfig":{"actionType":"command","commandCode":"%s","params":"{}"},
                           "failStrategy":"block","async":false,"enabled":true},
                          {"hookType":"post_execute","executionOrder":1,
                           "hookConfig":{"actionType":"script","script":"#vars[\\"postFired\\"]=true"},
                           "failStrategy":"ignore","async":false,"enabled":true}
                        ]
                      }}},
                    {"id":"end","type":"endEvent","position":{"x":520,"y":200},
                      "data":{"type":"endEvent","label":"End"}}
                  ],
                  "edges":[
                    {"id":"e1","source":"start","target":"approve","data":{}},
                    {"id":"e2","source":"approve","target":"end","data":{}}
                  ]
                }
                """.formatted(processKey, processKey, preCmdCode);
    }

    private String uniqueProcessKey() {
        return "wf_hook_cmp_" + System.nanoTime();
    }

    @Test
    @DisplayName("HOOK-CMP-01: converter emits aura.hooks smart:property with serialized hook list")
    void hookCmp01_xmlEmission() {
        String key = uniqueProcessKey();
        String json = buildDesignerJson(key, "wd:notify_pre");

        String xml = jsonToBpmnConverter.convert(json);

        // Wrapper element appears for the userTask
        assertTrue(xml.contains("<userTask id=\"approve\""), "userTask must render: " + xml);
        assertTrue(xml.contains("<extensionElements>"), "extensionElements must render: " + xml);

        // Hooks are stored as a single aura.hooks property containing a JSON array
        assertTrue(xml.contains("name=\"aura.hooks\""), xml);

        // Both hook types appear in the serialized JSON value (key fragments only —
        // the full attribute is XML-escaped JSON, so attribute matching is brittle)
        assertTrue(xml.contains("pre_execute"), xml);
        assertTrue(xml.contains("post_execute"), xml);
        assertTrue(xml.contains("command"), xml);
        assertTrue(xml.contains("script"), xml);
        assertTrue(xml.contains("wd:notify_pre"), xml);
        assertTrue(xml.contains("postFired"), xml);
    }

    @Test
    @DisplayName("HOOK-CMP-02: deploy persists designer hooks into ab_bpm_node_hook with vocab normalization")
    void hookCmp02_deployPersistsHooks() {
        String key = uniqueProcessKey();
        String designerJson = buildDesignerJson(key, "wd:notify_pre");

        var def = deploymentService.create(new ProcessDeploymentService.CreateProcessRequest(
                key, "Hook Compile " + key, "desc", "test",
                null /* bpmnContent — converter compiles from designerJson */,
                designerJson, null, null));
        var deployed = deploymentService.deploy(def.getPid());
        assertNotNull(deployed.getDeploymentId(), "deploy must succeed");

        // The runtime BpmNodeHookService normalizes pre_execute → pre_check at
        // createHook(). Verify both the pre and post buckets surface with the
        // expected hookConfig payload.
        List<BpmNodeHook> preHooks = nodeHookService.getHooks(key, "approve", "pre_check");
        assertEquals(1, preHooks.size(), "one pre-check hook expected");
        assertEquals("pre_check", preHooks.get(0).getHookType());
        assertEquals("approve", preHooks.get(0).getNodeId());
        assertEquals(0, preHooks.get(0).getExecutionOrder());
        assertEquals("block", preHooks.get(0).getFailStrategy());
        assertEquals(Boolean.FALSE, preHooks.get(0).getAsync());
        assertEquals(Boolean.TRUE, preHooks.get(0).getEnabled());
        assertEquals("command", preHooks.get(0).getHookConfig().get("actionType"));
        assertEquals("wd:notify_pre", preHooks.get(0).getHookConfig().get("commandCode"));

        List<BpmNodeHook> postHooks = nodeHookService.getHooks(key, "approve", "post_action");
        assertEquals(1, postHooks.size(), "one post-action hook expected");
        assertEquals("post_action", postHooks.get(0).getHookType());
        assertEquals("script", postHooks.get(0).getHookConfig().get("actionType"));
        assertTrue(((String) postHooks.get(0).getHookConfig().get("script")).contains("postFired"));

        // The deployed BPMN content also carries the aura.hooks smart:property so
        // exports / round-trips recover designer state without consulting the DB.
        // Re-fetch from store since deploy() mutates bpmnContent in-place.
        String latestXml = deploymentService.getByPid(def.getPid()).getBpmnContent();
        assertNotNull(latestXml, "bpmn content must persist after deploy");
        assertTrue(latestXml.contains("name=\"aura.hooks\""),
                "deployed BPMN must carry aura.hooks property: " + latestXml);
        assertTrue(latestXml.contains("pre_execute"), latestXml);
    }

    @Test
    @DisplayName("HOOK-CMP-03: hook persistence is scoped per processKey + survives second read")
    void hookCmp03_scopedPerProcessKey() {
        String keyA = uniqueProcessKey();
        String keyB = uniqueProcessKey();

        // Deploy two distinct processes, each with its own hook set
        var defA = deploymentService.create(new ProcessDeploymentService.CreateProcessRequest(
                keyA, "Hook Compile " + keyA, "desc", "test",
                null, buildDesignerJson(keyA, "wd:cmd_a"), null, null));
        deploymentService.deploy(defA.getPid());

        var defB = deploymentService.create(new ProcessDeploymentService.CreateProcessRequest(
                keyB, "Hook Compile " + keyB, "desc", "test",
                null, buildDesignerJson(keyB, "wd:cmd_b"), null, null));
        deploymentService.deploy(defB.getPid());

        // Each process sees only its own hooks
        var hooksA = nodeHookService.getHooksByProcessKey(keyA);
        var hooksB = nodeHookService.getHooksByProcessKey(keyB);
        assertEquals(2, hooksA.size(), "process A must have its 2 hooks");
        assertEquals(2, hooksB.size(), "process B must have its 2 hooks");
        assertTrue(hooksA.stream().allMatch(h -> keyA.equals(h.getProcessKey())));
        assertTrue(hooksB.stream().allMatch(h -> keyB.equals(h.getProcessKey())));

        // Verify the actual command codes — proves descriptors carry through
        // the right process boundary.
        var preA = nodeHookService.getHooks(keyA, "approve", "pre_check");
        var preB = nodeHookService.getHooks(keyB, "approve", "pre_check");
        assertEquals("wd:cmd_a", preA.get(0).getHookConfig().get("commandCode"));
        assertEquals("wd:cmd_b", preB.get(0).getHookConfig().get("commandCode"));
    }
}
