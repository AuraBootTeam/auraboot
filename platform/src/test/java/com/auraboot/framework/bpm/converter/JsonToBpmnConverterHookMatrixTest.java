package com.auraboot.framework.bpm.converter;

import com.auraboot.framework.bpm.entity.BpmNodeHook;
import com.auraboot.framework.bpm.service.BpmNodeHookService;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GAP-254 follow-up: full hookType × actionType combinatorial coverage.
 *
 * <p>The sibling {@link JsonToBpmnConverterHookCompilationIntegrationTest} uses
 * a fixed (pre_execute + post_execute) × (command + script) combo. This file
 * parameterizes over the complete 2 × 4 = 8-cell matrix supported by
 * {@code BpmNodeHookService}:
 *
 * <pre>
 *                 | rest_call | script | drools_rule | command
 *    pre_execute  |    ✓      |   ✓    |     ✓        |   ✓
 *    post_execute |    ✓      |   ✓    |     ✓        |   ✓
 * </pre>
 *
 * <p>The 4 (hookType) × 5 (actionType) matrix suggested in the task prompt
 * collapses to 8 cells at this layer because:
 * <ul>
 *   <li>Backend vocab recognizes only two {@code hookType} buckets — see
 *       {@code BpmNodeHookService.HOOK_TYPE_ALIASES}: {@code pre_execute /
 *       pre_check / pre_complete} all collapse to {@code pre_check}, and
 *       {@code post_execute / post_complete / post_action} all collapse to
 *       {@code post_action}. There is no {@code on_error} or {@code on_timeout}
 *       hook bucket at this layer; those would be separate BPMN constructs
 *       (boundaryEvents), not smart:hook extensions on userTasks.</li>
 *   <li>{@code actionType} at this layer supports
 *       {@code rest_call / script / drools_rule / command}. There is no
 *       {@code notification} action — the designer's notification UI compiles
 *       down to {@code command} with a notification-sending command code, or
 *       to {@code rest_call} with an email/IM webhook URL.</li>
 * </ul>
 * The test comments on each of the invalid-at-this-layer cells to keep the
 * intent explicit for future maintainers.
 */
@Slf4j
@DisplayName("JsonToBpmnConverter hook matrix (GAP-254 follow-up)")
class JsonToBpmnConverterHookMatrixTest extends BaseIntegrationTest {

    @Autowired
    private JsonToBpmnConverter jsonToBpmnConverter;

    @Autowired
    private ProcessDeploymentService deploymentService;

    @Autowired
    private BpmNodeHookService nodeHookService;

    /**
     * Designer-vocab hook types that the frontend emits. All three
     * {@code pre_*} aliases collapse to {@code pre_check}; all three
     * {@code post_*} aliases collapse to {@code post_action}. For the
     * combinatorial matrix we pick one canonical alias per backend bucket
     * ({@code pre_execute} and {@code post_execute}) to avoid testing the
     * alias normalization three times — that normalization is already
     * pinned in {@code JsonToBpmnConverterHookCompilationIntegrationTest}.
     */
    private static Stream<Arguments> hookActionMatrix() {
        // Each Arguments: (designerHookType, backendHookType, actionType,
        //                  designerHookConfig JSON fragment, assertion snippet)
        return Stream.of(
                // ----- pre_execute (→ pre_check) × 4 action types -----
                Arguments.of("pre_execute", "pre_check", "rest_call",
                        "\"actionType\":\"rest_call\",\"url\":\"https://example.test/pre\",\"method\":\"POST\""),
                Arguments.of("pre_execute", "pre_check", "script",
                        "\"actionType\":\"script\",\"script\":\"#vars[\\\"preScript\\\"]=1\""),
                Arguments.of("pre_execute", "pre_check", "drools_rule",
                        "\"actionType\":\"drools_rule\",\"ruleName\":\"pre-amount-check\""),
                Arguments.of("pre_execute", "pre_check", "command",
                        "\"actionType\":\"command\",\"commandCode\":\"wd:pre_notify\",\"params\":\"{}\""),
                // ----- post_execute (→ post_action) × 4 action types -----
                Arguments.of("post_execute", "post_action", "rest_call",
                        "\"actionType\":\"rest_call\",\"url\":\"https://example.test/post\",\"method\":\"POST\""),
                Arguments.of("post_execute", "post_action", "script",
                        "\"actionType\":\"script\",\"script\":\"#vars[\\\"postScript\\\"]=1\""),
                Arguments.of("post_execute", "post_action", "drools_rule",
                        "\"actionType\":\"drools_rule\",\"ruleName\":\"post-audit-rule\""),
                Arguments.of("post_execute", "post_action", "command",
                        "\"actionType\":\"command\",\"commandCode\":\"wd:post_audit\",\"params\":\"{}\"")
        );
    }

    private String buildDesignerJson(String processKey,
                                     String designerHookType,
                                     String hookConfigFragment) {
        return """
                {
                  "key": "%s",
                  "name": "Matrix %s",
                  "nodes": [
                    {"id":"start","type":"startEvent","position":{"x":80,"y":200},
                      "data":{"type":"startEvent","label":"Start"}},
                    {"id":"approve","type":"userTask","position":{"x":280,"y":200},
                      "data":{"type":"userTask","label":"Approve","config":{
                        "assigneeType":"user","assigneeIds":["admin"],
                        "hooks":[
                          {"hookType":"%s","executionOrder":0,
                           "hookConfig":{%s},
                           "failStrategy":"block","async":false,"enabled":true}
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
                """.formatted(processKey, processKey, designerHookType, hookConfigFragment);
    }

    @ParameterizedTest(name = "{0}[{1}] x {2}")
    @MethodSource("hookActionMatrix")
    @DisplayName("MATRIX: hookType × actionType compile → XML + persisted hook row")
    void hookMatrix(String designerHookType, String backendHookType, String actionType,
                    String hookConfigFragment) {
        String key = "wf_matrix_" + backendHookType + "_" + actionType + "_" + System.nanoTime();
        String designerJson = buildDesignerJson(key, designerHookType, hookConfigFragment);

        // Convert step: XML must carry the aura.hooks property and the action type token.
        String xml = jsonToBpmnConverter.convert(designerJson);
        assertTrue(xml.contains("name=\"aura.hooks\""),
                "XML must carry aura.hooks property for " + designerHookType + "/" + actionType
                        + "; got: " + xml);
        assertTrue(xml.contains(designerHookType),
                "XML must contain designer hookType token for drift detection: " + xml);
        assertTrue(xml.contains(actionType),
                "XML must contain actionType token " + actionType + ": " + xml);

        // Deploy step: hook row persisted under backend-normalized hookType.
        var def = deploymentService.create(new ProcessDeploymentService.CreateProcessRequest(
                key, "Matrix " + key, "matrix", "test",
                null /* let deploy compile from designerJson */,
                designerJson, null, null));
        var deployed = deploymentService.deploy(def.getPid());
        assertNotNull(deployed.getDeploymentId(), "deploy must succeed for " + actionType);

        // Read back through the service — this is the runtime path that
        // {@code executePreChecks / executePostActions} use at hook dispatch.
        List<BpmNodeHook> hooks = nodeHookService.getHooks(key, "approve", backendHookType);
        assertEquals(1, hooks.size(),
                "exactly one hook row expected for " + backendHookType + "/" + actionType);

        BpmNodeHook hook = hooks.get(0);
        assertEquals(backendHookType, hook.getHookType(),
                "hookType must be normalized to backend vocab");
        assertEquals("approve", hook.getNodeId(), "nodeId lock-in");

        Map<String, Object> cfg = hook.getHookConfig();
        assertNotNull(cfg, "hookConfig payload must persist");
        // actionType may be stored under either {@code actionType} (designer vocab)
        // or {@code type} (internal dispatch key) depending on the converter's
        // serialization strategy — accept either and assert the value shape.
        String storedAction = (String) cfg.getOrDefault("actionType", cfg.get("type"));
        assertNotNull(storedAction, "actionType/type must be present in hookConfig");
        assertEquals(actionType, storedAction,
                "hookConfig must retain the designer-provided actionType token");
    }
}
