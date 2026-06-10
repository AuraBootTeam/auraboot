package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import com.auraboot.framework.agent.mapper.AbCapabilityEvalRunMapper;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the LLM eval mode of {@link CapabilityEvalService} —
 * real-LLM selection scoring, hallucination counting, truthful mode
 * degradation, and per-case failure handling.
 */
@ExtendWith(MockitoExtension.class)
class CapabilityEvalLlmModeTest {

    @Mock
    private CapabilityViewService capabilityViewService;
    @Mock
    private ToolProviderRegistry toolProviderRegistry;
    @Mock
    private DynamicDataMapper dynamicDataMapper;
    @Mock
    private AbCapabilityEvalRunMapper evalRunMapper;
    @Mock
    private LlmToolSelectionService llmToolSelectionService;

    private CapabilityEvalService service;

    @BeforeEach
    void setUp() {
        service = new CapabilityEvalService(capabilityViewService, toolProviderRegistry,
                dynamicDataMapper, new ObjectMapper(), evalRunMapper, llmToolSelectionService);
        lenient().when(evalRunMapper.selectList(any())).thenReturn(List.of());
    }

    private static ToolDefinition tool(String code, String description) {
        ToolDefinition t = new ToolDefinition();
        t.setToolCode(code);
        t.setDescription(description);
        return t;
    }

    private static CapabilityEvalCase evalCase(String expectedTool) {
        return CapabilityEvalCase.builder()
                .caseId("EVAL-001")
                .taskDescription("Create a new sales order")
                .expectedToolCodes(List.of(expectedTool))
                .category("tool_selection")
                .build();
    }

    @Test
    void llmModeScoresLlmSelectionAndCountsHallucinations() throws Exception {
        when(llmToolSelectionService.isAvailable(1L)).thenReturn(true);
        when(toolProviderRegistry.discoverAll(any()))
                .thenReturn(List.of(tool("cmd_order_create", "Create order")));
        when(llmToolSelectionService.selectTools(anyLong(), anyString(), anyList(), anyInt()))
                .thenReturn(new LlmToolSelectionService.Selection(
                        List.of("cmd_order_create"), List.of("cmd_ghost")));

        Map<String, Object> report = service.evaluateToolSelection(
                1L, "llm", List.of(evalCase("cmd_order_create")));

        assertThat(report.get("evalMode")).isEqualTo("llm");
        assertThat(report.get("toolSelectionAccuracy")).isEqualTo(1.0);
        assertThat(report.get("hallucinationRate")).isEqualTo(1.0); // 1 of 1 cases hallucinated
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) report.get("cases");
        assertThat(cases.get(0).get("hallucinatedTools")).isEqualTo(List.of("cmd_ghost"));
    }

    @Test
    void llmModeWithoutProviderDegradesToKeywordTruthfully() throws Exception {
        when(llmToolSelectionService.isAvailable(1L)).thenReturn(false);
        when(toolProviderRegistry.discoverAll(any()))
                .thenReturn(List.of(tool("cmd_order_create", "Create a new sales order")));

        Map<String, Object> report = service.evaluateToolSelection(
                1L, "llm", List.of(evalCase("cmd_order_create")));

        assertThat(report.get("evalMode")).isEqualTo("keyword");
        verify(llmToolSelectionService, never()).selectTools(anyLong(), anyString(), anyList(), anyInt());
    }

    @Test
    void llmCaseFailureScoresAsEmptySelectionNotKeywordSwap() throws Exception {
        when(llmToolSelectionService.isAvailable(1L)).thenReturn(true);
        when(toolProviderRegistry.discoverAll(any()))
                .thenReturn(List.of(tool("cmd_order_create", "Create order")));
        when(llmToolSelectionService.selectTools(anyLong(), anyString(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("rate limited"));

        Map<String, Object> report = service.evaluateToolSelection(
                1L, "llm", List.of(evalCase("cmd_order_create")));

        assertThat(report.get("evalMode")).isEqualTo("llm");
        assertThat(report.get("toolSelectionAccuracy")).isEqualTo(0.0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) report.get("cases");
        assertThat(cases.get(0).get("llmError")).isEqualTo("rate limited");
        assertThat(cases.get(0).get("toolSelectionCorrect")).isEqualTo(false);
    }

    @Test
    void keywordModeNeverTouchesLlmService() throws Exception {
        when(toolProviderRegistry.discoverAll(any()))
                .thenReturn(List.of(tool("cmd_order_create", "Create a new sales order")));

        Map<String, Object> report = service.evaluateToolSelection(
                1L, "keyword", List.of(evalCase("cmd_order_create")));

        assertThat(report.get("evalMode")).isEqualTo("keyword");
        verify(llmToolSelectionService, never()).isAvailable(anyLong());
        verify(llmToolSelectionService, never()).selectTools(anyLong(), anyString(), anyList(), anyInt());
    }
}
