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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Dimension 2 (parameter completion) must be scored against the case's declared
 * {@code expectedInputKeys}, not inferred from tool selection.
 *
 * <p><strong>Why this exists.</strong> The parameter dimension used to be a proxy:
 * "if the expected tool was selected, assume the params are satisfiable". That makes
 * the 20%-weighted parameter score unfalsifiable — a model that picks the right tool
 * but names none of the required arguments scored a perfect 1.0 on parameter
 * completion. These tests pin the real behaviour: the model must actually name every
 * expected argument, and a case that declares no expected keys is reported as using
 * the proxy so a run's score can be read honestly.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Capability eval: parameter completion scored against expectedInputKeys")
class CapabilityEvalParameterScoringTest {

    @Mock private CapabilityViewService capabilityViewService;
    @Mock private ToolProviderRegistry toolProviderRegistry;
    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private AbCapabilityEvalRunMapper evalRunMapper;
    @Mock private LlmToolSelectionService llmToolSelectionService;
    @Mock private AgentEvalCaseMapper agentEvalCaseMapper;

    private CapabilityEvalService service;

    private static final Long TENANT_ID = 1L;
    private static final String TOOL = "cmd_create_order";

    @BeforeEach
    void setUp() {
        service = new CapabilityEvalService(capabilityViewService, toolProviderRegistry,
                dynamicDataMapper, new ObjectMapper(), evalRunMapper, llmToolSelectionService,
                agentEvalCaseMapper);
        lenient().when(evalRunMapper.selectList(any())).thenReturn(List.of());
        lenient().when(llmToolSelectionService.isAvailable(any())).thenReturn(true);
        lenient().when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(tool(TOOL)));
    }

    private static ToolDefinition tool(String code) {
        ToolDefinition t = new ToolDefinition();
        t.setToolCode(code);
        t.setDescription("Tool " + code);
        return t;
    }

    /** Stub the model's reply: it picks {@code TOOL} and reports these argument names. */
    private void modelReports(List<String> params) throws Exception {
        when(llmToolSelectionService.selectTools(any(), anyString(), anyList(), anyInt()))
                .thenReturn(new LlmToolSelectionService.Selection(List.of(TOOL), List.of(), params));
    }

    private static CapabilityEvalCase caseExpecting(Map<String, Object> expectedInputKeys) {
        return CapabilityEvalCase.builder()
                .caseId("PARAM-001")
                .taskDescription("Create a sales order for customer C-1001, product P-9, quantity 20")
                .expectedToolCodes(List.of(TOOL))
                .expectedInputKeys(expectedInputKeys)
                .category("parameter_fill")
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstCase(Map<String, Object> report) {
        List<Map<String, Object>> cases = (List<Map<String, Object>>) report.get("cases");
        assertThat(cases).as("report must carry per-case results").isNotEmpty();
        return cases.get(0);
    }

    @Test
    @DisplayName("naming every expected argument scores parameter completion 1.0")
    void allExpectedKeysReported_scoresFull() throws Exception {
        modelReports(List.of("customer_id", "sku", "qty"));

        Map<String, Object> report = service.evaluateToolSelection(TENANT_ID, "llm",
                List.of(caseExpecting(Map.of("customer_id", "C-1001", "sku", "P-9", "qty", 20))));

        assertThat(((Number) report.get("parameterCompletionRate")).doubleValue()).isEqualTo(1.0);
        Map<String, Object> c = firstCase(report);
        assertThat(c.get("parameterScoring")).isEqualTo("expectedInputKeys");
        assertThat(c.get("parameterComplete")).isEqualTo(true);
        assertThat(c).doesNotContainKey("missingParams");
    }

    /**
     * The falsifying case: right tool, missing arguments. Under the old proxy this
     * scored 1.0; it must now score 0.0 and name what was missing.
     */
    @Test
    @DisplayName("right tool but missing an expected argument scores 0.0 and names the gap")
    void missingExpectedKey_scoresZeroAndReportsGap() throws Exception {
        modelReports(List.of("customer_id"));  // sku + qty never named

        Map<String, Object> report = service.evaluateToolSelection(TENANT_ID, "llm",
                List.of(caseExpecting(Map.of("customer_id", "C-1001", "sku", "P-9", "qty", 20))));

        // Tool selection is still correct — this isolates the parameter dimension.
        assertThat(((Number) report.get("toolSelectionAccuracy")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) report.get("parameterCompletionRate")).doubleValue()).isEqualTo(0.0);

        Map<String, Object> c = firstCase(report);
        assertThat(c.get("parameterComplete")).isEqualTo(false);
        assertThat((List<String>) c.get("missingParams")).containsExactlyInAnyOrder("sku", "qty");
    }

    /** Reporting no arguments at all is "no evidence", never a silent pass. */
    @Test
    @DisplayName("reporting no arguments at all scores 0.0, not a silent pass")
    void noParamsReported_scoresZero() throws Exception {
        modelReports(List.of());

        Map<String, Object> report = service.evaluateToolSelection(TENANT_ID, "llm",
                List.of(caseExpecting(Map.of("customer_id", "C-1001"))));

        assertThat(((Number) report.get("parameterCompletionRate")).doubleValue()).isEqualTo(0.0);
        assertThat(firstCase(report).get("parameterComplete")).isEqualTo(false);
    }

    /**
     * A case that declares no expected keys has nothing to check, so it falls back to
     * the tool-selection proxy — but the report must say so, so a reader can tell a
     * real parameter score from an inherited one.
     */
    @Test
    @DisplayName("a case with no expectedInputKeys falls back to the proxy and labels it")
    void noExpectedKeys_fallsBackToProxyAndLabelsIt() throws Exception {
        modelReports(List.of());

        Map<String, Object> report = service.evaluateToolSelection(TENANT_ID, "llm",
                List.of(caseExpecting(Map.of())));

        // Proxy: the tool was selected correctly, so the parameter dimension inherits that.
        assertThat(((Number) report.get("parameterCompletionRate")).doubleValue()).isEqualTo(1.0);
        assertThat(firstCase(report).get("parameterScoring")).isEqualTo("proxy:tool-selection");
    }
}
