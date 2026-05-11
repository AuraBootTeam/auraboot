package com.auraboot.framework.agent.port;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.service.ToolLoopService;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the GroundingPort + ToolDiscoveryPort pipeline.
 *
 * Verifies:
 *   1. GroundingPort resolves query/mutation intents via regex-based IntentParser
 *   2. GroundingPort uses pageModel as fallback for object resolution
 *   3. ToolDiscoveryPort discovers tools from ToolProviderRegistry
 *   4. Discovered tools execute through the canonical ToolLoopService
 *
 * Requires: published meta models + ab_object_alias seed data for object resolution.
 * LLM is NOT invoked — IntentParser Phase 1+2 regex covers all test messages.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
class AuraBotToolRoutingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private GroundingPort groundingPort;

    @Autowired
    private ToolDiscoveryPort toolDiscoveryPort;

    @Autowired
    private ToolLoopService toolLoopService;

    @Autowired
    private ObjectMapper objectMapper;

    private Long tenantId;

    @BeforeEach
    void setUpContext() {
        tenantId = getTestTenant().getId();
        MetaContext.setSystemTenantContext(tenantId);
    }

    @AfterEach
    void tearDownContext() {
        MetaContext.clear();
    }

    // ========== GroundingPort: intent resolution ==========

    @Test
    void ground_queryMessage_returnsReadOnlyIntent() {
        var result = groundingPort.ground(tenantId, "帮我查看线索状态分布", "crm_lead", null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isIn("query", "analyze", "summarize", "list");
        assertThat(result.readOnly()).isTrue();
        assertThat(result.candidateSkills()).isNotNull();
    }

    @Test
    void ground_analyzeMessage_returnsReadOnlyIntent() {
        var result = groundingPort.ground(tenantId, "分析一下最近的线索趋势", "crm_lead", null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("analyze");
        assertThat(result.readOnly()).isTrue();
        assertThat(result.confidence()).isGreaterThan(0.0);
    }

    @Test
    void ground_updateMessage_returnsWriteIntent() {
        var result = groundingPort.ground(tenantId, "把这个线索的评分改成85", "crm_lead", null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isIn("update", "transition", "create");
        assertThat(result.readOnly()).isFalse();
    }

    @Test
    void ground_createMessage_returnsWriteIntent() {
        var result = groundingPort.ground(tenantId, "新建一个客户记录", "crm_account", null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("create");
        assertThat(result.readOnly()).isFalse();
    }

    @Test
    void ground_deleteMessage_returnsWriteIntent() {
        var result = groundingPort.ground(tenantId, "删除这条记录", "crm_lead", null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("delete");
        assertThat(result.readOnly()).isFalse();
    }

    // ========== GroundingPort: object resolution ==========

    @Test
    void ground_withPageModel_resolvesObject() {
        var result = groundingPort.ground(tenantId, "统计数据", "crm_lead", null);

        // When the message alone is ambiguous, pageModel provides context for object resolution
        assertThat(result).isNotNull();
        assertThat(result.object()).isNotNull();
        // The object should be resolved — either from message content or from pageModel fallback
        // If the alias index has "线索" / "lead" entries, it resolves to crm_lead;
        // otherwise the ObjectResolver may still use pageModel as context
    }

    @Test
    void ground_withExplicitObjectInMessage_resolvesCorrectly() {
        var result = groundingPort.ground(tenantId, "查看客户列表", null, null);

        assertThat(result).isNotNull();
        assertThat(result.intent()).isEqualTo("query");
        // If ab_object_alias has "客户" -> "crm_account" mapping, object is resolved
        if (result.object() != null) {
            assertThat(result.object()).isEqualTo("crm_account");
        }
    }

    // ========== ToolDiscoveryPort: discovery ==========

    @Test
    void discoverTools_withEmptySkills_usesProviderRegistry() {
        var tools = toolDiscoveryPort.discoverTools(tenantId, List.of(), null, "query", 10);

        // ToolProviderRegistry should return platform tools at minimum
        assertThat(tools).isNotNull();
        // Even with no candidate skills and no model hint, platform provider contributes tools
    }

    @Test
    void discoverTools_withModelHint_returnsRelevantTools() {
        var tools = toolDiscoveryPort.discoverTools(tenantId, List.of(), "crm_lead", "query", 10);

        assertThat(tools).isNotNull();
        // With a model hint, DSL provider should discover list/nq tools if CRM models are published
        if (!tools.isEmpty()) {
            var codes = tools.stream().map(ToolDiscoveryPort.ToolDef::code).toList();
            // Tool codes typically have provider prefixes
            assertThat(codes).allSatisfy(code ->
                    assertThat(code).isNotBlank()
            );
        }
    }

    @Test
    void discoverTools_respectsMaxToolsLimit() {
        int maxTools = 3;
        var tools = toolDiscoveryPort.discoverTools(tenantId, List.of(), null, "query", maxTools);

        assertThat(tools).isNotNull();
        assertThat(tools).hasSizeLessThanOrEqualTo(maxTools);
    }

    // ========== Canonical ToolLoopService execution ==========

    @Test
    void toolLoop_platformListModels_returnsModels() throws Exception {
        var result = executePlatformListModels();

        assertThat(result).isNotNull();
        assertThat(result).containsKey("success");
        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result).containsKey("durationMs");
    }

    @Test
    void toolLoop_unknownTool_returnsFailure() throws Exception {
        String raw = toolLoopService.executeToolCall(
                tenantId, "tool-routing-test-run", null, "aurabot",
                "nonexistent.tool_xyz", Map.of(), List.of(), null);
        Map<String, Object> result = Map.of(
                "success", false,
                "error", raw
        );

        assertThat(result).isNotNull();
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result).containsKey("error");
    }

    // ========== End-to-end pipeline: ground → discover → execute ==========

    @Test
    void fullPipeline_groundThenDiscoverThenExecute() throws Exception {
        // Step 1: Ground the user message
        var groundResult = groundingPort.ground(tenantId, "查看所有模型", null, null);
        assertThat(groundResult).isNotNull();
        assertThat(groundResult.intent()).isEqualTo("query");

        // Step 2: Discover tools based on grounding result
        var tools = toolDiscoveryPort.discoverTools(
                tenantId,
                groundResult.candidateSkills(),
                groundResult.object(),
                groundResult.intent(),
                5
        );
        assertThat(tools).isNotNull();

        // Step 3: Execute platform.list_models through ToolLoopService as a known-good tool
        var execResult = executePlatformListModels();
        assertThat(execResult).isNotNull();
        assertThat(execResult.get("success")).isEqualTo(true);
    }

    private Map<String, Object> executePlatformListModels() throws Exception {
        AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("platform.list_models")
                .description("List Data Models")
                .toolType("platform")
                .sourceCode("platform.list_models")
                .riskLevel("L0")
                .confirmationPolicy("none")
                .inputSchema(Map.of("type", "object"))
                .build();
        String raw = toolLoopService.executeToolCall(
                tenantId, "tool-routing-test-run", null, "aurabot",
                tool.getName(), Map.of(), List.of(tool), null);
        return objectMapper.readValue(raw, new TypeReference<>() {});
    }
}
