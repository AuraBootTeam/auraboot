package com.auraboot.framework.bpm.handler;

import com.auraboot.framework.bpm.entity.BpmRule;
import com.auraboot.framework.bpm.rule.DroolsRuleService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.BpmRuleDefinitionDTO;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link BpmRunRuleHandler}.
 * Uses real DroolsEngineService + PostgreSQL rule store.
 */
@DisplayName("BPM run-rule handler integration tests")
class BpmRunRuleHandlerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BpmRunRuleHandler handler;

    @Autowired
    private DroolsRuleService droolsRuleService;

    @Autowired
    private ExtensionRegistry extensionRegistry;

    private static final String DRL_APPROVER_ROLE = """
            package com.auraboot.test.rules
            import java.util.Map

            rule "assign_approver_role"
                when
                    $m : Map( this["ruleKind"] == "assignee" )
                then
                    Map result = (Map) $m.get("_ruleResult");
                    result.put("approverRole", "manager");
            end
            """;

    private static final String DRL_VALIDATION = """
            package com.auraboot.test.rules
            import java.util.Map

            rule "leave_validation_fail"
                when
                    $m : Map( this["days"] != null && ((Number) this["days"]).intValue() > 30 )
                then
                    Map result = (Map) $m.get("_ruleResult");
                    result.put("valid", false);
                    result.put("reason", "bpm.rule.leave_days_exceeded");
            end
            """;

    @Test
    @DisplayName("Handler is discoverable via ExtensionRegistry by commandCode")
    void registryResolvesHandlerByCommandCode() {
        Optional<?> resolved = extensionRegistry.getCommandHandler(BpmRunRuleHandler.COMMAND_CODE);
        assertThat(resolved).isPresent();
        assertThat(resolved.get()).isInstanceOf(BpmRunRuleHandler.class);
    }

    @Test
    @DisplayName("Executes DRL and returns _ruleResult contents to the caller")
    void executeReturnsRuleResult() {
        String ruleCode = "test_assign_" + System.nanoTime();
        BpmRule rule = droolsRuleService.importRule(BpmRuleDefinitionDTO.builder()
                .ruleCode(ruleCode)
                .ruleName("Test Assignee Rule")
                .ruleType("ASSIGNEE")
                .ruleContent(DRL_APPROVER_ROLE)
                .enabled(true)
                .build());
        assertThat(rule.getPid()).isNotBlank();

        Map<String, Object> payload = new HashMap<>();
        payload.put(BpmRunRuleHandler.ARG_RULE_CODE, ruleCode);
        Map<String, Object> facts = new HashMap<>();
        facts.put("ruleKind", "assignee");
        payload.put(BpmRunRuleHandler.ARG_FACTS, facts);

        CommandContext ctx = CommandContext.builder()
                .tenantId(getTestTenant().getId())
                .commandType(BpmRunRuleHandler.COMMAND_CODE)
                .payload(payload)
                .build();

        Object result = handler.execute(ctx);
        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) result;
        assertThat(out).containsEntry("approverRole", "manager");
    }

    @Test
    @DisplayName("Rule-reported valid=false raises BusinessException with reason as i18n key")
    void executeFailsWhenRuleReportsInvalid() {
        String ruleCode = "test_validate_" + System.nanoTime();
        droolsRuleService.importRule(BpmRuleDefinitionDTO.builder()
                .ruleCode(ruleCode)
                .ruleName("Leave validation")
                .ruleType("VALIDATION")
                .ruleContent(DRL_VALIDATION)
                .enabled(true)
                .build());

        Map<String, Object> payload = new HashMap<>();
        payload.put(BpmRunRuleHandler.ARG_RULE_CODE, ruleCode);
        Map<String, Object> facts = new HashMap<>();
        facts.put("days", 45);
        payload.put(BpmRunRuleHandler.ARG_FACTS, facts);

        CommandContext ctx = CommandContext.builder()
                .tenantId(getTestTenant().getId())
                .commandType(BpmRunRuleHandler.COMMAND_CODE)
                .payload(payload)
                .build();

        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("bpm.rule.leave_days_exceeded");
    }

    @Test
    @DisplayName("Missing ruleCode raises BusinessException with i18n key")
    void executeFailsWhenRuleCodeMissing() {
        CommandContext ctx = CommandContext.builder()
                .tenantId(getTestTenant().getId())
                .commandType(BpmRunRuleHandler.COMMAND_CODE)
                .payload(Map.of())
                .build();

        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(BpmRunRuleHandler.ERR_RULE_CODE_REQUIRED);
    }

    @Test
    @DisplayName("Unknown ruleCode raises BusinessException")
    void executeFailsWhenRuleNotFound() {
        CommandContext ctx = CommandContext.builder()
                .tenantId(getTestTenant().getId())
                .commandType(BpmRunRuleHandler.COMMAND_CODE)
                .payload(Map.of(BpmRunRuleHandler.ARG_RULE_CODE, "does_not_exist_" + System.nanoTime()))
                .build();

        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(BpmRunRuleHandler.ERR_EXECUTION_FAILED);
    }
}
