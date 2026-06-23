package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LlmToolSelectionService} — availability contract,
 * known/hallucinated partitioning, and failure propagation.
 */
@ExtendWith(MockitoExtension.class)
class LlmToolSelectionServiceTest {

    @Mock
    private LlmProviderFactory llmProviderFactory;
    @Mock
    private LlmProvider provider;

    private LlmToolSelectionService service;

    private final List<ToolDefinition> catalog = List.of(
            tool("cmd_order_create", "Create a sales order"),
            tool("cmd_order_cancel", "Cancel a sales order"),
            tool("nq_order_list", "List sales orders"));

    private static ToolDefinition tool(String code, String description) {
        ToolDefinition t = new ToolDefinition();
        t.setToolCode(code);
        t.setDescription(description);
        return t;
    }

    private static ToolDefinition tool(String code, String description, String riskLevel) {
        ToolDefinition t = tool(code, description);
        t.setRiskLevel(riskLevel);
        return t;
    }

    @BeforeEach
    void setUp() {
        service = new LlmToolSelectionService(llmProviderFactory, new ObjectMapper());
    }

    private void stubProvider(String replyText) throws Exception {
        LlmProviderFactory.ProviderConfig config = LlmProviderFactory.ProviderConfig.builder()
                .providerCode("anthropic").apiKey("k").defaultModel("m").build();
        when(llmProviderFactory.resolveConfig(anyLong(), eq("anthropic"))).thenReturn(config);
        when(llmProviderFactory.getProvider(anyString())).thenReturn(provider);
        when(provider.chat(any(LlmChatRequest.class), anyString(), any()))
                .thenReturn(LlmChatResponse.builder()
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("text").text(replyText).build()))
                        .build());
    }

    @Test
    void notAvailableWithoutConfiguredProvider() {
        when(llmProviderFactory.resolveConfig(anyLong(), eq("anthropic"))).thenReturn(null);
        when(llmProviderFactory.listConfiguredProviders(anyLong())).thenReturn(List.of());

        assertThat(service.isAvailable(1L)).isFalse();
    }

    @Test
    void selectsKnownToolsAndPartitionsHallucinated() throws Exception {
        stubProvider("{\"tools\": [\"cmd_order_create\", \"cmd_invented\", \"nq_order_list\"]}");

        LlmToolSelectionService.Selection selection =
                service.selectTools(1L, "create an order then list", catalog, 5);

        assertThat(selection.selected()).containsExactly("cmd_order_create", "nq_order_list");
        assertThat(selection.hallucinated()).containsExactly("cmd_invented");
    }

    @Test
    void respectsMaxToolsAndDeduplicates() throws Exception {
        stubProvider("{\"tools\": [\"cmd_order_create\", \"cmd_order_create\", \"cmd_order_cancel\", \"nq_order_list\"]}");

        LlmToolSelectionService.Selection selection =
                service.selectTools(1L, "order things", catalog, 2);

        assertThat(selection.selected()).containsExactly("cmd_order_create", "cmd_order_cancel");
    }

    @Test
    void malformedReplyThrows() throws Exception {
        stubProvider("Sure, you should use the order creation tool!");

        assertThatThrownBy(() -> service.selectTools(1L, "create order", catalog, 5))
                .isInstanceOf(Exception.class);
    }

    @Test
    void missingToolsArrayThrows() throws Exception {
        stubProvider("{\"selection\": []}");

        assertThatThrownBy(() -> service.selectTools(1L, "create order", catalog, 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'tools' array");
    }

    @Test
    void promptListsCatalogAndForbidsInvention() throws Exception {
        stubProvider("{\"tools\": []}");

        service.selectTools(1L, "create order", catalog, 5);

        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        org.mockito.Mockito.verify(provider).chat(captor.capture(), anyString(), any());
        String prompt = captor.getValue().getSystemPrompt();
        assertThat(prompt)
                .contains("cmd_order_create")
                .contains("nq_order_list")
                .contains("Never invent codes")
                .contains("query, diagnose, gather context");
    }

    @Test
    void readOnlyIntentDropsMutatingSelections() throws Exception {
        List<ToolDefinition> qualityCatalog = List.of(
                tool("dsl.query", "Query/read quality records and CAPA history (read-only)", "L0"),
                tool("qc:create_capa", "Create a CAPA draft — WRITE, needs confirmation", "L3"),
                tool("qc:close_quality", "Close a quality exception record — WRITE", "L2"));
        stubProvider("{\"tools\": [\"dsl.query\", \"qc:create_capa\", \"qc:close_quality\"]}");

        LlmToolSelectionService.Selection selection =
                service.selectTools(1L, "先获取质量异常趋势和 CAPA 上下文,不要直接动质量记录。", qualityCatalog, 5);

        assertThat(selection.selected()).containsExactly("dsl.query");
    }

    @Test
    void readOnlyIntentFallsBackToCatalogReadToolWhenModelOnlySelectsMutating() throws Exception {
        List<ToolDefinition> qualityCatalog = List.of(
                tool("dsl.query", "Query/read quality records and CAPA history (read-only)", "L0"),
                tool("qc:create_capa", "Create a CAPA draft — WRITE, needs confirmation", "L3"));
        stubProvider("{\"tools\": [\"qc:create_capa\"]}");

        LlmToolSelectionService.Selection selection =
                service.selectTools(1L, "gather context first, do not act yet", qualityCatalog, 5);

        assertThat(selection.selected()).containsExactly("dsl.query");
    }

    @Test
    void actionIntentKeepsMutatingTool() throws Exception {
        List<ToolDefinition> qualityCatalog = List.of(
                tool("dsl.query", "Query/read quality records and CAPA history (read-only)", "L0"),
                tool("qc:create_capa", "Create a CAPA draft — WRITE, needs confirmation", "L3"));
        stubProvider("{\"tools\": [\"qc:create_capa\"]}");

        LlmToolSelectionService.Selection selection =
                service.selectTools(1L, "针对缺陷记录 PE-DEF-001,生成一份 CAPA 草稿。", qualityCatalog, 5);

        assertThat(selection.selected()).containsExactly("qc:create_capa");
    }
}
