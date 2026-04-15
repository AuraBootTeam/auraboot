package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.bpm.converter.JsonToBpmnConverter;
import com.auraboot.framework.bpm.entity.BpmRule;
import com.auraboot.framework.bpm.rule.DroolsRuleService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.BpmRuleDefinitionDTO;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that proves the new {@link DroolsServiceTaskDelegate}
 * actually fires when SmartEngine executes a deployed BPMN process.
 *
 * <p>Real {@link com.auraboot.framework.bpm.rule.DroolsEngineService} +
 * real PostgreSQL rule store + real {@link SmartEngine}. No mocks.
 *
 * <p>Each test:
 * <ol>
 *   <li>Imports a Drools rule via {@link DroolsRuleService} (rule output writes
 *       {@code approverRole}).</li>
 *   <li>Builds a 4-node BPMN process via {@link JsonToBpmnConverter} —
 *       {@code startEvent → rule-task(droolsServiceTaskDelegate) → endEvent}.</li>
 *   <li>Deploys the BPMN XML to SmartEngine.</li>
 *   <li>Starts a process with input vars and asserts the rule output landed
 *       in the process variables.</li>
 * </ol>
 */
@DisplayName("DroolsServiceTaskDelegate integration tests (real BPMN + real SmartEngine)")
class DroolsServiceTaskDelegateIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DroolsRuleService droolsRuleService;

    @Autowired
    private JsonToBpmnConverter jsonToBpmnConverter;

    @Autowired
    private SmartEngine smartEngine;

    /**
     * Drools rule that branches on {@code days}: ≤3 → manager, otherwise → hr.
     * Writes the result into the special {@code _ruleResult} map keyed
     * {@code approverRole}, which {@link DroolsServiceTaskDelegate} merges
     * back into the process variables.
     */
    private static final String DRL_ROUTING = """
            package com.auraboot.test.rules
            import java.util.Map

            rule "route_short_leave"
                when
                    $m : Map( this["days"] != null && ((Number) this["days"]).intValue() <= 3 )
                then
                    Map result = (Map) $m.get("_ruleResult");
                    result.put("approverRole", "manager");
            end

            rule "route_long_leave"
                when
                    $m : Map( this["days"] != null && ((Number) this["days"]).intValue() > 3 )
                then
                    Map result = (Map) $m.get("_ruleResult");
                    result.put("approverRole", "hr");
            end
            """;

    private String importRoutingRule(String suffix) {
        String code = "it_routing_" + suffix + "_" + System.nanoTime();
        BpmRule rule = droolsRuleService.importRule(BpmRuleDefinitionDTO.builder()
                .ruleCode(code)
                .ruleName("Routing rule")
                .ruleType("ASSIGNEE")
                .ruleContent(DRL_ROUTING)
                .enabled(true)
                .build());
        assertThat(rule.getPid()).isNotBlank();
        return code;
    }

    /** Build the rule-task process JSON using designer schema the converter knows. */
    private Map<String, Object> ruleTaskProcessJson(String processKey, String ruleCode) {
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("id", "start_1");
        start.put("type", "startEvent");
        start.put("data", Map.of("label", "Start"));

        Map<String, Object> ruleTaskData = new LinkedHashMap<>();
        ruleTaskData.put("label", "Routing");
        ruleTaskData.put("ruleCode", ruleCode);
        ruleTaskData.put("factsVars", "days");
        Map<String, Object> ruleTask = new LinkedHashMap<>();
        ruleTask.put("id", "svc_route");
        ruleTask.put("type", "rule-task");
        ruleTask.put("data", ruleTaskData);

        Map<String, Object> end = new LinkedHashMap<>();
        end.put("id", "end_1");
        end.put("type", "endEvent");
        end.put("data", Map.of("label", "End"));

        Map<String, Object> e1 = Map.of("id", "edge_1", "source", "start_1", "target", "svc_route");
        Map<String, Object> e2 = Map.of("id", "edge_2", "source", "svc_route", "target", "end_1");

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("key", processKey);
        json.put("name", "Routing test");
        json.put("nodes", List.of(start, ruleTask, end));
        json.put("edges", List.of(e1, e2));
        return json;
    }

    private String deploy(String processKey, String ruleCode) {
        String xml = jsonToBpmnConverter.convertFromMap(ruleTaskProcessJson(processKey, ruleCode));
        // Sanity: XML actually wires the smart:class delegate.
        assertThat(xml).contains("smart:class=\"" + BpmServiceTaskConstants.BEAN_DROOLS_DELEGATE + "\"");
        assertThat(xml).contains("smart:" + BpmServiceTaskConstants.ATTR_RULE_CODE + "=\"" + ruleCode + "\"");
        smartEngine.getRepositoryCommandService().deployWithUTF8Content(xml);

        // Confirm SmartEngine knows about it; capture the version SmartEngine assigned
        // (the converter does not emit a version attribute, so SmartEngine picks a default).
        var pd = smartEngine.getRepositoryQueryService()
                .getAllCachedProcessDefinition()
                .stream()
                .filter(p -> processKey.equals(p.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "BPMN should be cached by SmartEngine, processKey=" + processKey));
        return pd.getVersion();
    }

    @Test
    @DisplayName("days=2 → rule output approverRole=manager merged into process vars")
    void shortLeave_routesToManager() {
        String ruleCode = importRoutingRule("short");
        String processKey = "it_drl_proc_short_" + System.nanoTime();
        String version = deploy(processKey, ruleCode);

        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 2);
        // tenantId required by SmartEngine multi-tenant routing
        vars.put("sys_tenant_id", String.valueOf(getTestTenant().getId()));

        ProcessInstance instance = smartEngine.getProcessCommandService()
                .start(processKey, version, vars);

        assertThat(instance).isNotNull();
        assertThat(instance.getInstanceId()).isNotBlank();
        // Drools delegate must have merged approverRole back into process variables.
        assertThat(vars)
                .as("approverRole must be set by DroolsServiceTaskDelegate after rule fires")
                .containsEntry("approverRole", "manager");
    }

    @Test
    @DisplayName("days=5 → rule output approverRole=hr merged into process vars")
    void longLeave_routesToHr() {
        String ruleCode = importRoutingRule("long");
        String processKey = "it_drl_proc_long_" + System.nanoTime();
        String version = deploy(processKey, ruleCode);

        Map<String, Object> vars = new HashMap<>();
        vars.put("days", 5);
        vars.put("sys_tenant_id", String.valueOf(getTestTenant().getId()));

        ProcessInstance instance = smartEngine.getProcessCommandService()
                .start(processKey, version, vars);

        assertThat(instance).isNotNull();
        assertThat(vars)
                .as("approverRole must be 'hr' for days > 3")
                .containsEntry("approverRole", "hr");
    }
}
