package com.auraboot.framework.bpm;

import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G-B1 — server-side designerJson pre-deploy validation.
 *
 * Exercises {@link ProcessDeploymentService#validateDesignerJson} against the real
 * converter so the designer can surface deploy-blocking errors before the user
 * clicks Deploy (previously validation happened only at deploy time, where the
 * SmartEngine root cause is easily swallowed).
 */
@DisplayName("BPM designerJson server-side validation (G-B1)")
class ProcessDesignerJsonValidationIT extends BaseIntegrationTest {

    @Autowired
    private ProcessDeploymentService deploymentService;

    private static final String VALID_JSON = """
            {
              "key":"valid_proc","name":"Valid",
              "nodes":[
                {"id":"s","type":"startEvent","position":{"x":0,"y":0},"data":{"type":"startEvent","label":"Start"}},
                {"id":"t","type":"userTask","position":{"x":1,"y":0},"data":{"type":"userTask","label":"Approve"}},
                {"id":"e","type":"endEvent","position":{"x":2,"y":0},"data":{"type":"endEvent","label":"End"}}
              ],
              "edges":[
                {"id":"f1","source":"s","target":"t","data":{}},
                {"id":"f2","source":"t","target":"e","data":{}}
              ]
            }
            """;

    @Test
    @DisplayName("valid designerJson -> valid=true, no errors")
    void validJson() {
        ProcessDeploymentService.ValidationResult r =
                deploymentService.validateDesignerJson(VALID_JSON, "valid_proc", "Valid");
        assertTrue(r.valid(), () -> "expected valid, errors=" + r.errors());
        assertTrue(r.errors().isEmpty(), () -> r.errors().toString());
    }

    @Test
    @DisplayName("empty designerJson -> invalid with message")
    void emptyJson() {
        ProcessDeploymentService.ValidationResult r =
                deploymentService.validateDesignerJson("  ", "k", "n");
        assertFalse(r.valid());
        assertFalse(r.errors().isEmpty());
    }

    @Test
    @DisplayName("unsupported node type -> invalid, error names the offending node (G-B2 surfaced via G-B1)")
    void unsupportedNodeType() {
        String json = """
                {
                  "key":"bad_node","name":"Bad",
                  "nodes":[
                    {"id":"s","type":"startEvent","position":{"x":0,"y":0},"data":{"type":"startEvent","label":""}},
                    {"id":"weird","type":"businessRuleTask","position":{"x":1,"y":0},"data":{"type":"businessRuleTask","label":"X"}},
                    {"id":"e","type":"endEvent","position":{"x":2,"y":0},"data":{"type":"endEvent","label":""}}
                  ],
                  "edges":[
                    {"id":"f1","source":"s","target":"weird","data":{}},
                    {"id":"f2","source":"weird","target":"e","data":{}}
                  ]
                }
                """;
        ProcessDeploymentService.ValidationResult r =
                deploymentService.validateDesignerJson(json, "bad_node", "Bad");
        assertFalse(r.valid(), "unsupported node type must be rejected");
        assertTrue(r.errors().stream().anyMatch(m -> m.contains("businessRuleTask")),
                () -> "error should name the offending type, got " + r.errors());
    }

    @Test
    @DisplayName("exclusive gateway flow missing condition -> invalid")
    void exclusiveGatewayMissingCondition() {
        // exclusive gateway with two outgoing flows but no conditions — SmartEngine
        // has no bare default fallback, so the converter rejects it.
        String json = """
                {
                  "key":"gw_nocond","name":"GW",
                  "nodes":[
                    {"id":"s","type":"startEvent","position":{"x":0,"y":0},"data":{"type":"startEvent","label":""}},
                    {"id":"gw","type":"exclusiveGateway","position":{"x":1,"y":0},"data":{"type":"exclusiveGateway","label":"Choose"}},
                    {"id":"a","type":"userTask","position":{"x":2,"y":0},"data":{"type":"userTask","label":"A"}},
                    {"id":"b","type":"userTask","position":{"x":2,"y":1},"data":{"type":"userTask","label":"B"}},
                    {"id":"e","type":"endEvent","position":{"x":3,"y":0},"data":{"type":"endEvent","label":""}}
                  ],
                  "edges":[
                    {"id":"f1","source":"s","target":"gw","data":{}},
                    {"id":"f2","source":"gw","target":"a","data":{}},
                    {"id":"f3","source":"gw","target":"b","data":{}},
                    {"id":"f4","source":"a","target":"e","data":{}},
                    {"id":"f5","source":"b","target":"e","data":{}}
                  ]
                }
                """;
        ProcessDeploymentService.ValidationResult r =
                deploymentService.validateDesignerJson(json, "gw_nocond", "GW");
        assertFalse(r.valid(), "exclusive gateway flows without conditions must be rejected");
        assertFalse(r.errors().isEmpty());
    }
}
