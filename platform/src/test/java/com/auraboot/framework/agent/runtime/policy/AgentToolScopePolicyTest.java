package com.auraboot.framework.agent.runtime.policy;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B4 (quality-state 2026-07-20): the colleague wizard's "allowed models" and
 * "allowed operations" checkboxes were write-only — configured, displayed,
 * enforced nowhere. This locks the semantics of the policy that now binds them
 * at tool-assembly time on both engines.
 */
class AgentToolScopePolicyTest {

    private final AgentToolScopePolicy policy = new AgentToolScopePolicy(new ObjectMapper());

    private ToolDefinition dslCommand(String toolCode, String sourceCode, String modelCode, String kind) {
        return ToolDefinition.builder()
                .toolCode(toolCode).toolName(toolCode).toolType("dsl_command")
                .sourceCode(sourceCode).modelCode(modelCode).operationKind(kind)
                .build();
    }

    private ToolDefinition dslQuery(String toolCode, String modelCode) {
        return ToolDefinition.builder()
                .toolCode(toolCode).toolName(toolCode).toolType("dsl_query")
                .sourceCode(modelCode).modelCode(modelCode).operationKind("query")
                .build();
    }

    private Map<String, Object> agentDef(Object models, Object ops) {
        Map<String, Object> def = new HashMap<>();
        def.put("allowed_models", models);
        def.put("allowed_operations", ops);
        return def;
    }

    // ---- allowed_models axis -------------------------------------------------

    @Test
    void nullStarAndEmptyAllowedModelsMeanUnrestricted() {
        List<ToolDefinition> tools = List.of(dslQuery("list:crm_lead", "crm_lead"));
        for (Object models : new Object[]{null, "*", List.of()}) {
            AgentToolScopePolicy.Scope scope = policy.scopeOf(agentDef(models, null));
            assertThat(policy.filterDefinitions(scope, tools, "a")).hasSize(1);
        }
    }

    @Test
    void stampedModelCodeIsMatchedExactly() {
        AgentToolScopePolicy.Scope scope = policy.scopeOf(agentDef(List.of("crm_account"), null));
        List<ToolDefinition> tools = List.of(
                dslQuery("list:crm_account", "crm_account"),
                dslQuery("list:crm_lead", "crm_lead"),
                dslCommand("cmd:crm:create_lead", "crm:create_lead", "crm_lead", "create"));
        assertThat(policy.filterDefinitions(scope, tools, "a"))
                .extracting(ToolDefinition::getToolCode)
                .containsExactly("list:crm_account");
    }

    @Test
    void allowedModelsAcceptsJsonStringColumnValue() {
        AgentToolScopePolicy.Scope scope = policy.scopeOf(agentDef("[\"crm_account\"]", null));
        List<ToolDefinition> tools = List.of(
                dslQuery("list:crm_account", "crm_account"),
                dslQuery("list:crm_lead", "crm_lead"));
        assertThat(policy.filterDefinitions(scope, tools, "a"))
                .extracting(ToolDefinition::getToolCode)
                .containsExactly("list:crm_account");
    }

    @Test
    void nonModelToolsPassBothAxes() {
        AgentToolScopePolicy.Scope scope = policy.scopeOf(agentDef(List.of("crm_account"), List.of("query")));
        List<ToolDefinition> tools = List.of(
                ToolDefinition.builder().toolCode("escalate_to_human").toolType("custom").build(),
                ToolDefinition.builder().toolCode("platform.delegate_task").toolType("platform").build(),
                ToolDefinition.builder().toolCode("mcp:srv:thing").toolType("mcp").build());
        assertThat(policy.filterDefinitions(scope, tools, "a")).hasSize(3);
    }

    @Test
    void unstampedDslToolFallsBackToLegacyPrefixMatch() {
        AgentToolScopePolicy.Scope scope = policy.scopeOf(agentDef(List.of("crm_account"), null));
        // skill-resolved tools carry no modelCode; the legacy heuristic keeps
        // same-plugin tools (prefix match in either direction) and drops others
        List<AgentToolDefinition> tools = List.of(
                AgentToolDefinition.builder().name("cmd:crm:list_accounts").toolType("dsl_query")
                        .sourceCode("crm:list_accounts").build(),
                AgentToolDefinition.builder().name("cmd:iot:reboot").toolType("dsl_command")
                        .sourceCode("iot:reboot").build());
        assertThat(policy.filterAgentTools(scope, tools, "a"))
                .extracting(AgentToolDefinition::getName)
                .containsExactly("cmd:crm:list_accounts");
    }

    // ---- allowed_operations axis --------------------------------------------

    @Test
    void queryOnlyAgentLosesEveryWriteTool() {
        AgentToolScopePolicy.Scope scope = policy.scopeOf(agentDef(null, List.of("query")));
        List<ToolDefinition> tools = List.of(
                dslQuery("list:crm_lead", "crm_lead"),
                dslCommand("cmd:crm:create_lead", "crm:create_lead", "crm_lead", "create"),
                dslCommand("cmd:crm:delete_lead", "crm:delete_lead", "crm_lead", "delete"));
        assertThat(policy.filterDefinitions(scope, tools, "a"))
                .extracting(ToolDefinition::getToolCode)
                .containsExactly("list:crm_lead");
    }

    @Test
    void uncheckingDeleteAloneBlocksExactlyDelete() {
        // The original B4 report: "取消勾选 delete 并不阻止删除"
        AgentToolScopePolicy.Scope scope = policy.scopeOf(
                agentDef(null, List.of("query", "create", "update", "transition")));
        List<ToolDefinition> tools = List.of(
                dslCommand("cmd:crm:create_lead", "crm:create_lead", "crm_lead", "create"),
                dslCommand("cmd:crm:delete_lead", "crm:delete_lead", "crm_lead", "delete"),
                dslCommand("cmd:crm:close_lead", "crm:close_lead", "crm_lead", "state_transition"));
        assertThat(policy.filterDefinitions(scope, tools, "a"))
                .extracting(ToolDefinition::getToolCode)
                .containsExactly("cmd:crm:create_lead", "cmd:crm:close_lead");
    }

    @Test
    void unstampedCommandVerbIsInferredFromItsName() {
        AgentToolScopePolicy.Scope scope = policy.scopeOf(
                agentDef(null, List.of("query", "create")));
        List<AgentToolDefinition> tools = List.of(
                AgentToolDefinition.builder().name("cmd:crm:create_ticket").toolType("dsl_command")
                        .sourceCode("crm:create_ticket").build(),
                AgentToolDefinition.builder().name("cmd:crm:delete_ticket").toolType("dsl_command")
                        .sourceCode("crm:delete_ticket").build());
        assertThat(policy.filterAgentTools(scope, tools, "a"))
                .extracting(AgentToolDefinition::getName)
                .containsExactly("cmd:crm:create_ticket");
    }

    @Test
    void unclassifiableWriteKindNeedsSomeWriteVerb() {
        ToolDefinition odd = dslCommand("cmd:crm:reconcile", "crm:reconcile", "crm_lead", "automate");
        AgentToolScopePolicy.Scope readOnly = policy.scopeOf(agentDef(null, List.of("query")));
        AgentToolScopePolicy.Scope writer = policy.scopeOf(agentDef(null, List.of("query", "update")));
        assertThat(policy.filterDefinitions(readOnly, List.of(odd), "a")).isEmpty();
        assertThat(policy.filterDefinitions(writer, List.of(odd), "a")).hasSize(1);
    }

    @Test
    void bothAxesMustAgree() {
        AgentToolScopePolicy.Scope scope = policy.scopeOf(
                agentDef(List.of("crm_account"), List.of("query")));
        List<ToolDefinition> tools = List.of(
                dslQuery("list:crm_account", "crm_account"),
                dslCommand("cmd:crm:create_account", "crm:create_account", "crm_account", "create"),
                dslQuery("list:crm_lead", "crm_lead"));
        assertThat(policy.filterDefinitions(scope, tools, "a"))
                .extracting(ToolDefinition::getToolCode)
                .containsExactly("list:crm_account");
    }

    @Test
    void unparseableColumnIsTreatedAsUnrestrictedNotAsDenyAll() {
        AgentToolScopePolicy.Scope scope = policy.scopeOf(agentDef("not-json", null));
        assertThat(policy.filterDefinitions(scope,
                List.of(dslQuery("list:crm_lead", "crm_lead")), "a")).hasSize(1);
    }
}
