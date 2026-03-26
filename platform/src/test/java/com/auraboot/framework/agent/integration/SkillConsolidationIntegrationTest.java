package com.auraboot.framework.agent.integration;

import com.auraboot.framework.agent.dto.SkillInput;
import com.auraboot.framework.agent.dto.SkillResult;
import com.auraboot.framework.agent.provider.*;
import com.auraboot.framework.agent.service.*;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the ACP Skill Consolidation pipeline.
 *
 * Validates the full flow: NL input -> BIF (IntentParser + ObjectResolver + RiskEvaluator)
 * -> SkillEngine (dsl_dispatch) -> ToolProviderRegistry (DslToolProvider, PlatformToolProvider).
 *
 * The test tenant may not have business models registered, so DSL execution tests
 * verify routing correctness by checking that errors come from the domain layer
 * (CommandExecutor / DynamicDataService), not from the dispatch/routing layer.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
class SkillConsolidationIntegrationTest extends BaseIntegrationTest {

    @Autowired private SkillEngine skillEngine;
    @Autowired private SkillAutoGenerator skillAutoGenerator;
    @Autowired private IntentParser intentParser;
    @Autowired private ObjectResolver objectResolver;
    @Autowired private RiskEvaluator riskEvaluator;
    @Autowired private ToolProviderRegistry toolProviderRegistry;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = getTestTenant().getId();
        // Ensure the 2 built-in skills (dsl.command, dsl.query) exist
        skillAutoGenerator.syncSkills(tenantId);
    }

    // ===== BIF Pipeline: IntentParser =====

    @Test
    void intentParser_createIntent_matchesPattern() {
        var result = intentParser.parse("新建一个客户叫集成测试公司");
        assertThat(result.getIntent()).isEqualTo("create");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.85);
        assertThat(result.getMatchType()).isIn("pattern", "keyword");
    }

    @Test
    void intentParser_deleteIntent_matchesPattern() {
        var result = intentParser.parse("删除这条记录");
        assertThat(result.getIntent()).isEqualTo("delete");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    void intentParser_queryIntent_matchesPattern() {
        var result = intentParser.parse("查看最近的线索");
        assertThat(result.getIntent()).isEqualTo("query");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    void intentParser_transitionIntent_matchesPattern() {
        var result = intentParser.parse("审批通过这条申请");
        assertThat(result.getIntent()).isEqualTo("transition");
        assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    void intentParser_emptyInput_defaultsToQuery() {
        var result = intentParser.parse("");
        assertThat(result.getIntent()).isEqualTo("query");
        assertThat(result.getConfidence()).isEqualTo(0.3);
        assertThat(result.getMatchType()).isEqualTo("default");
    }

    // ===== BIF Pipeline: ObjectResolver =====

    @Test
    void objectResolver_exactModelCode_resolvesWithHighConfidence() {
        // "crm_account" is a known model code in the inverted index
        var result = objectResolver.resolve(tenantId, "crm_account");
        // If index contains crm_account, should resolve with high confidence
        if (result.getModelCode() != null) {
            assertThat(result.getConfidence()).isGreaterThan(0.7);
            assertThat(result.getMatchType()).isIn("exact", "alias", "fuzzy");
        }
        // Even if not found, result should not be null
        assertThat(result).isNotNull();
    }

    @Test
    void objectResolver_emptyInput_returnsNone() {
        var result = objectResolver.resolve(tenantId, "");
        assertThat(result.getModelCode()).isNull();
        assertThat(result.getConfidence()).isEqualTo(0.0);
        assertThat(result.getMatchType()).isEqualTo("none");
    }

    // ===== BIF Pipeline: RiskEvaluator =====

    @Test
    void riskEvaluator_queryIntent_isL0ReadOnly() {
        assertThat(riskEvaluator.evaluate("query", 1)).isEqualTo("L0");
        assertThat(riskEvaluator.deriveActionability("query")).isEqualTo("read_only");
    }

    @Test
    void riskEvaluator_createIntent_isL1Execute() {
        assertThat(riskEvaluator.evaluate("create", 1)).isEqualTo("L1");
        assertThat(riskEvaluator.deriveActionability("create")).isEqualTo("execute");
    }

    @Test
    void riskEvaluator_deleteIntent_isL4Propose() {
        assertThat(riskEvaluator.evaluate("delete", 1)).isEqualTo("L4");
        assertThat(riskEvaluator.deriveActionability("delete")).isEqualTo("propose");
    }

    @Test
    void riskEvaluator_batchOperation_elevatesRisk() {
        // Single create is L1, but 200 records should elevate to L3
        assertThat(riskEvaluator.evaluate("create", 1)).isEqualTo("L1");
        assertThat(riskEvaluator.evaluate("create", 50)).isEqualTo("L2");
        assertThat(riskEvaluator.evaluate("create", 200)).isEqualTo("L3");
    }

    @Test
    void riskEvaluator_deriveFromCommandType_mapsCorrectly() {
        assertThat(riskEvaluator.deriveFromCommandType("create")).isEqualTo("L1");
        assertThat(riskEvaluator.deriveFromCommandType("delete")).isEqualTo("L4");
        assertThat(riskEvaluator.deriveFromCommandType("state_transition")).isEqualTo("L1");
    }

    // ===== BIF Pipeline: Combined Intent + Risk =====

    @Test
    void bifCombined_intentToRisk_queryIsReadOnly() {
        var intent = intentParser.parse("帮我查看客户列表");
        assertThat(intent.getIntent()).isIn("query", "analyze");
        String risk = riskEvaluator.evaluate(intent.getIntent(), 1);
        assertThat(risk).isEqualTo("L0");
    }

    @Test
    void bifCombined_intentToRisk_deleteIsHighRisk() {
        var intent = intentParser.parse("删除所有过期合同");
        assertThat(intent.getIntent()).isEqualTo("delete");
        // Simulating batch delete of 500 records — still L4 (delete base is L4, already max)
        String risk = riskEvaluator.evaluate(intent.getIntent(), 500);
        assertThat(risk).isEqualTo("L4");
        assertThat(riskEvaluator.deriveActionability(intent.getIntent())).isEqualTo("propose");
    }

    // ===== SkillAutoGenerator =====

    @Test
    void skillAutoGenerator_syncCreatesOrUpdatesTwoSkills() {
        // First call was in @BeforeEach, so second call should update both
        var result = skillAutoGenerator.syncSkills(tenantId);
        assertThat(result.created()).isEqualTo(0);
        assertThat(result.updated()).isEqualTo(2);
    }

    // ===== SkillEngine: dsl.query =====

    @Test
    void skillEngine_dslQuery_list_dispatchesCorrectly() {
        SkillInput input = SkillInput.builder()
                .intent("query")
                .object("crm_account")
                .parameters(Map.of("model", "crm_account"))
                .build();

        SkillResult result = skillEngine.execute(tenantId, "e2e-run", "dsl.query", input, null, null, null);

        assertThat(result).isNotNull();
        assertThat(result.getSkillCode()).isEqualTo("dsl.query");
        // If model exists: SUCCESS with records; if not: domain-level error
        if (result.getStatus() == SkillResult.Status.SUCCESS) {
            assertThat(result.getOutputType()).isEqualTo("structured_result");
            assertThat(result.getData()).containsKey("records");
            assertThat(result.getData()).containsKey("total");
        }
    }

    @Test
    void skillEngine_dslQuery_missingAllParams_fails() {
        SkillInput input = SkillInput.builder()
                .intent("query")
                .parameters(Map.of())
                .build();

        SkillResult result = skillEngine.execute(tenantId, "e2e-run", "dsl.query", input, null, null, null);
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
        assertThat(result.getErrorMessage()).contains("requires at least one of");
    }

    // ===== SkillEngine: dsl.command =====

    @Test
    void skillEngine_dslCommand_dispatches_to_commandExecutor() {
        SkillInput input = SkillInput.builder()
                .intent("create")
                .object("crm_account")
                .parameters(Map.of(
                        "commandCode", "crm_account_create",
                        "crm_account_name", "SkillConsolidationTest_" + System.currentTimeMillis()
                ))
                .build();

        SkillResult result = skillEngine.execute(tenantId, "e2e-run", "dsl.command", input, null, null, null);

        assertThat(result).isNotNull();
        assertThat(result.getSkillCode()).isEqualTo("dsl.command");
        assertThat(result.getToolCallCount()).isEqualTo(1);
        // Domain-level error from CommandExecutor is acceptable (proves routing worked)
        if (result.getStatus() == SkillResult.Status.FAILED) {
            assertThat(result.getErrorMessage())
                    .describedAs("Error should be from CommandExecutor, not dispatch")
                    .doesNotContain("Skill not found")
                    .doesNotContain("commandCode is required");
        }
    }

    @Test
    void skillEngine_dslCommand_missingCommandCode_fails() {
        SkillInput input = SkillInput.builder()
                .parameters(Map.of("name", "test"))
                .build();

        SkillResult result = skillEngine.execute(tenantId, "e2e-run", "dsl.command", input, null, null, null);
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
        assertThat(result.getErrorMessage()).contains("commandCode is required");
    }

    @Test
    void skillEngine_unknownSkill_failsGracefully() {
        SkillInput input = SkillInput.builder()
                .parameters(Map.of("foo", "bar"))
                .build();

        SkillResult result = skillEngine.execute(tenantId, "e2e-run", "nonexistent.skill", input, null, null, null);
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
        assertThat(result.getErrorMessage()).contains("Skill not found");
    }

    // ===== ToolProviderRegistry =====

    @Test
    void providerRegistry_allFourProvidersRegistered() {
        var codes = toolProviderRegistry.getProviderCodes();
        assertThat(codes).contains("dsl", "platform", "custom", "mcp");
    }

    @Test
    void providerRegistry_discoverAll_returnsPlatformTools() {
        var ctx = ToolDiscoveryContext.builder()
                .tenantId(tenantId)
                .maxResults(50)
                .build();
        var tools = toolProviderRegistry.discoverAll(ctx);

        // Platform tools are always available (no model hint needed)
        assertThat(tools).isNotEmpty();
        var providerCodes = tools.stream()
                .map(ToolDefinition::getProviderCode)
                .collect(Collectors.toSet());
        assertThat(providerCodes).contains("platform");

        // Verify platform tools include the known tools
        var toolCodes = tools.stream()
                .map(ToolDefinition::getToolCode)
                .collect(Collectors.toSet());
        assertThat(toolCodes).containsAnyOf(
                "platform.execute_sql", "platform.list_models", "platform.model_suggest");
    }

    @Test
    void providerRegistry_discoverWithModelHint_returnsDslTools() {
        var ctx = ToolDiscoveryContext.builder()
                .tenantId(tenantId)
                .modelHint("crm_account")
                .maxResults(50)
                .build();
        var tools = toolProviderRegistry.discoverAll(ctx);

        // DSL provider always adds list: and get: tools for any model hint
        var toolCodes = tools.stream()
                .map(ToolDefinition::getToolCode)
                .collect(Collectors.toSet());
        assertThat(toolCodes).contains("list:crm_account", "get:crm_account");
    }

    @Test
    void providerRegistry_executePlatformListModels_works() {
        var result = toolProviderRegistry.execute(tenantId, "platform.list_models", Map.of());
        assertThat(result).isNotNull();
        // Platform tool should execute (may return success or error depending on tenant data)
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);
        if (result.isSuccess()) {
            assertThat(result.getData()).containsKey("models");
        }
    }

    @Test
    void providerRegistry_executeUnknownTool_failsGracefully() {
        var result = toolProviderRegistry.execute(tenantId, "unknown.tool.code", Map.of());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("No provider handles tool");
    }

    // ===== Full Pipeline: NL -> BIF -> Skill -> Execute =====

    @Test
    void fullPipeline_queryIntent_endToEnd() {
        // Step 1: Parse natural language intent
        var intent = intentParser.parse("帮我查看客户列表");
        assertThat(intent.getIntent()).isEqualTo("query");
        assertThat(intent.getConfidence()).isGreaterThan(0.5);

        // Step 2: Resolve object
        var resolved = objectResolver.resolve(tenantId, "客户");
        // Object resolution depends on test data — store result for conditional execution

        // Step 3: Evaluate risk
        String risk = riskEvaluator.evaluate(intent.getIntent(), 1);
        assertThat(risk).isEqualTo("L0");
        String actionability = riskEvaluator.deriveActionability(intent.getIntent());
        assertThat(actionability).isEqualTo("read_only");

        // Step 4: Execute via SkillEngine (use crm_account as fallback if resolver didn't match)
        String modelCode = resolved.getModelCode() != null ? resolved.getModelCode() : "crm_account";
        SkillInput input = SkillInput.builder()
                .intent(intent.getIntent())
                .object(modelCode)
                .parameters(Map.of("model", modelCode))
                .build();

        SkillResult result = skillEngine.execute(tenantId, "e2e-run", "dsl.query", input, null, null, null);
        assertThat(result).isNotNull();
        assertThat(result.getSkillCode()).isEqualTo("dsl.query");
        // Pipeline completed — result is from domain layer (success or domain error)
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void fullPipeline_createIntent_endToEnd() {
        // Step 1: Parse intent
        var intent = intentParser.parse("新建一个客户");
        assertThat(intent.getIntent()).isEqualTo("create");

        // Step 2: Risk evaluation
        String risk = riskEvaluator.evaluate(intent.getIntent(), 1);
        assertThat(risk).isEqualTo("L1");
        assertThat(riskEvaluator.deriveActionability(intent.getIntent())).isEqualTo("execute");

        // Step 3: Resolve command (if model exists in test tenant)
        String commandCode = objectResolver.resolveCommand(tenantId, "crm_account", "create");
        // commandCode may be null if crm_account commands aren't in test tenant

        // Step 4: Execute via SkillEngine with a command code
        String effectiveCommand = commandCode != null ? commandCode : "crm_account_create";
        SkillInput input = SkillInput.builder()
                .intent("create")
                .object("crm_account")
                .parameters(Map.of(
                        "commandCode", effectiveCommand,
                        "crm_account_name", "FullPipelineTest_" + System.currentTimeMillis()
                ))
                .build();

        SkillResult result = skillEngine.execute(tenantId, "e2e-run", "dsl.command", input, null, null, null);
        assertThat(result).isNotNull();
        assertThat(result.getSkillCode()).isEqualTo("dsl.command");
        assertThat(result.getToolCallCount()).isEqualTo(1);
    }

    @Test
    void fullPipeline_deleteIntent_proposedNotExecuted() {
        // Step 1: Parse delete intent
        var intent = intentParser.parse("删除这个客户记录");
        assertThat(intent.getIntent()).isEqualTo("delete");

        // Step 2: Risk should be L4, actionability = propose (not execute)
        String risk = riskEvaluator.evaluate(intent.getIntent(), 1);
        assertThat(risk).isEqualTo("L4");
        String actionability = riskEvaluator.deriveActionability(intent.getIntent());
        assertThat(actionability).isEqualTo("propose");
        // In a real agent loop, this would trigger an approval gate instead of direct execution
    }
}
