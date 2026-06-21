package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import com.auraboot.framework.agent.mapper.AbCapabilityEvalRunMapper;
import com.auraboot.framework.agent.mapper.AgentEvalCaseMapper;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.auraboot.framework.agent.entity.AbCapabilityEvalRun;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for D3a dependency-aware skip:
 * when a case's expectedToolCodes are entirely absent from the tenant's tool catalog,
 * the case must be marked {@code status=unavailable} and excluded from accuracy denominators.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("D3a: unavailable cases excluded from accuracy denominators")
class CapabilityEvalUnavailableCaseTest {

    @Mock private CapabilityViewService capabilityViewService;
    @Mock private ToolProviderRegistry toolProviderRegistry;
    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private AbCapabilityEvalRunMapper evalRunMapper;
    @Mock private LlmToolSelectionService llmToolSelectionService;
    @Mock private AgentEvalCaseMapper agentEvalCaseMapper;

    private CapabilityEvalService service;

    private static final Long TENANT_ID = 1L;

    @BeforeEach
    void setUp() {
        service = new CapabilityEvalService(capabilityViewService, toolProviderRegistry,
                dynamicDataMapper, new ObjectMapper(), evalRunMapper, llmToolSelectionService,
                agentEvalCaseMapper);
        lenient().when(evalRunMapper.selectList(any())).thenReturn(List.of());
        lenient().when(llmToolSelectionService.isAvailable(any())).thenReturn(false);
    }

    private static ToolDefinition tool(String code) {
        ToolDefinition t = new ToolDefinition();
        t.setToolCode(code);
        t.setDescription("Tool " + code);
        return t;
    }

    /**
     * A case whose expected tool is NOT in the catalog must yield status=unavailable
     * and must NOT count toward totalCases (the accuracy denominator).
     */
    @Test
    @DisplayName("case with expected tool absent from catalog is marked unavailable, not failed")
    void expectedToolAbsentFromCatalog_markedUnavailableNotFailed() {
        // Catalog contains only "dsl.query" — NOT the case's expected tool
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(tool("dsl.query")));

        CapabilityEvalCase unavailableCase = CapabilityEvalCase.builder()
                .caseId("UNAVAIL-001")
                .taskDescription("Execute plugin-specific operation")
                .expectedToolCodes(List.of("plugin.special_tool"))   // absent from catalog
                .category("tool_selection")
                .build();

        Map<String, Object> result = service.evaluateToolSelection(TENANT_ID, "keyword",
                List.of(unavailableCase));

        // All-unavailable run short-circuits to no_scoreable_cases — mirrors the no_cases contract.
        assertThat(result.get("status")).isEqualTo("no_scoreable_cases");
        // The scoreable denominator must be 0 (not 1)
        assertThat(result.get("totalCases")).isEqualTo(0);
        // The unavailable counter must be 1
        assertThat(result.get("unavailableCases")).isEqualTo(1);

        // The per-case results are still returned for observability
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) result.get("cases");
        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).get("status")).isEqualTo("unavailable");
        assertThat(cases.get(0).get("caseId")).isEqualTo("UNAVAIL-001");

        // Must NOT persist: no_scoreable_cases must not pollute RegressionGate baselines
        verify(evalRunMapper, never()).insert(any(AbCapabilityEvalRun.class));
    }

    /**
     * A scoreable case (expected tool IS in the catalog) runs normally — accuracy
     * denominator includes it. An unavailable case in the same run is excluded.
     */
    @Test
    @DisplayName("mixed run: scoreable case contributes to denominator, unavailable case does not")
    void mixedRun_scoreableCaseCountedUnavailableExcluded() {
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(tool("dsl.query")));

        CapabilityEvalCase scoreableCase = CapabilityEvalCase.builder()
                .caseId("SCOREABLE-001")
                .taskDescription("query alarm list")
                .expectedToolCodes(List.of("dsl.query"))   // present in catalog
                .category("tool_selection")
                .build();

        CapabilityEvalCase unavailableCase = CapabilityEvalCase.builder()
                .caseId("UNAVAIL-001")
                .taskDescription("execute absent plugin operation")
                .expectedToolCodes(List.of("absent.tool"))  // NOT in catalog
                .category("tool_selection")
                .build();

        Map<String, Object> result = service.evaluateToolSelection(TENANT_ID, "keyword",
                List.of(scoreableCase, unavailableCase));

        // Only the scoreable case counts toward totalCases
        assertThat(result.get("totalCases")).isEqualTo(1);
        assertThat(result.get("unavailableCases")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) result.get("cases");
        // Both cases appear in the result list
        assertThat(cases).hasSize(2);

        Map<String, Object> unavailResult = cases.stream()
                .filter(c -> "UNAVAIL-001".equals(c.get("caseId")))
                .findFirst().orElseThrow();
        assertThat(unavailResult.get("status")).isEqualTo("unavailable");

        Map<String, Object> scoreableResult = cases.stream()
                .filter(c -> "SCOREABLE-001".equals(c.get("caseId")))
                .findFirst().orElseThrow();
        // Scoreable case is scored normally — no "unavailable" status key
        assertThat(scoreableResult).doesNotContainKey("status");
    }
}
