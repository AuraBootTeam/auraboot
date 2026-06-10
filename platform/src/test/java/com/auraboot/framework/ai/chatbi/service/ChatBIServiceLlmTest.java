package com.auraboot.framework.ai.chatbi.service;

import com.auraboot.framework.ai.chatbi.dto.ChatBIRequest;
import com.auraboot.framework.ai.chatbi.dto.ChatBIResponse;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the LLM-first parse path of {@link ChatBIService} — verifies
 * parse-mode reporting, anti-hallucination field validation, parameterized
 * filters, and keyword fallback when the LLM path is unavailable.
 */
@ExtendWith(MockitoExtension.class)
class ChatBIServiceLlmTest {

    @Mock
    private MetaModelService metaModelService;
    @Mock
    private DynamicDataMapper dynamicDataMapper;
    @Mock
    private ChatBiLlmParser chatBiLlmParser;

    private ChatBIService service;

    private ModelDefinition orderModel;

    @BeforeEach
    void setUp() {
        com.auraboot.framework.application.tenant.MetaContext.setContext(1L, 1L, "test-user-pid", "test-user");
        service = new ChatBIService(metaModelService, dynamicDataMapper, chatBiLlmParser);
        orderModel = ModelDefinition.builder()
                .code("sales_order")
                .displayName("Sales Order")
                .tableName("ab_dyn_sales_order")
                .fields(List.of(
                        FieldDefinition.builder().code("id").dataType("integer").build(),
                        FieldDefinition.builder().code("status").dataType("string").build(),
                        FieldDefinition.builder().code("amount").dataType("decimal").build()))
                .build();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        com.auraboot.framework.application.tenant.MetaContext.clear();
    }

    private ChatBIRequest request(String question, String modelCode) {
        ChatBIRequest req = new ChatBIRequest();
        req.setQuestion(question);
        req.setModelCode(modelCode);
        return req;
    }

    @Test
    void llmParseProducesLlmModeResponseWithGroupByAndParameterizedFilter() {
        when(metaModelService.getModelDefinition("sales_order")).thenReturn(Optional.of(orderModel));
        when(chatBiLlmParser.tryParse(any(), anyString(), any(), any()))
                .thenReturn(ChatBiLlmParser.ParsedQuery.builder()
                        .modelCode("sales_order")
                        .aggregationFunction("count")
                        .groupByField("status")
                        .sortOrder("desc")
                        .filters(List.of(ChatBiLlmParser.ParsedFilter.builder()
                                .fieldCode("amount").operator("GT").value(100).build()))
                        .build());
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("status", "open", "count_value", 3)));

        ChatBIResponse response = service.analyzeQuestion(request("count orders over 100 by status", "sales_order"));

        assertThat(response.getParseMode()).isEqualTo("llm");
        assertThat(response.getSql()).contains("GROUP BY status");
        assertThat(response.getSql()).contains("amount > #{params.f0}");
        assertThat(response.getSql()).doesNotContain("100"); // value bound, never inlined

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(dynamicDataMapper).selectByQuery(anyString(), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).containsEntry("f0", 100);
    }

    @Test
    void hallucinatedGroupByAndFilterFieldsAreDropped() {
        when(metaModelService.getModelDefinition("sales_order")).thenReturn(Optional.of(orderModel));
        when(chatBiLlmParser.tryParse(any(), anyString(), any(), any()))
                .thenReturn(ChatBiLlmParser.ParsedQuery.builder()
                        .modelCode("sales_order")
                        .aggregationFunction("count")
                        .groupByField("ghost_field")
                        .filters(List.of(ChatBiLlmParser.ParsedFilter.builder()
                                .fieldCode("not_a_field").operator("EQ").value("x").build()))
                        .build());
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of());

        ChatBIResponse response = service.analyzeQuestion(request("count by ghost", "sales_order"));

        assertThat(response.getParseMode()).isEqualTo("llm");
        assertThat(response.getSql()).doesNotContain("GROUP BY");
        assertThat(response.getSql()).doesNotContain("not_a_field");
    }

    @Test
    void llmUnavailableFallsBackToKeywordParsing() {
        when(metaModelService.getModelDefinition("sales_order")).thenReturn(Optional.of(orderModel));
        when(chatBiLlmParser.tryParse(any(), anyString(), any(), any())).thenReturn(null);
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap()))
                .thenReturn(List.of(Map.of("count_value", 7)));

        ChatBIResponse response = service.analyzeQuestion(request("how many sales orders", "sales_order"));

        assertThat(response.getParseMode()).isEqualTo("keyword");
        assertThat(response.getSql()).contains("COUNT(*)");
    }

    @Test
    void llmChoosingUnknownModelFallsBackToKeyword() {
        when(metaModelService.getModelDefinition("sales_order")).thenReturn(Optional.of(orderModel));
        when(metaModelService.getModelDefinition("invented_model")).thenReturn(Optional.empty());
        when(chatBiLlmParser.tryParse(any(), anyString(), any(), any()))
                .thenReturn(ChatBiLlmParser.ParsedQuery.builder().modelCode("invented_model").build());
        // tryLlmParse with explicit modelCode echoes explicit model; force the
        // unknown-model branch by omitting modelCode and stubbing the catalog.
        when(metaModelService.searchModels(eq(1), eq(100), any(), any(), any(), any(), any(), any(), any(), any(), eq(true)))
                .thenReturn(pageOf("sales_order"));
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of());

        ChatBIResponse response = service.analyzeQuestion(request("how many sales_order records", null));

        assertThat(response.getParseMode()).isEqualTo("keyword");
        assertThat(response.getModelCode()).isEqualTo("sales_order");
    }

    @Test
    void likeFilterValueIsWrappedWithWildcards() {
        when(metaModelService.getModelDefinition("sales_order")).thenReturn(Optional.of(orderModel));
        when(chatBiLlmParser.tryParse(any(), anyString(), any(), any()))
                .thenReturn(ChatBiLlmParser.ParsedQuery.builder()
                        .modelCode("sales_order")
                        .filters(List.of(ChatBiLlmParser.ParsedFilter.builder()
                                .fieldCode("status").operator("LIKE").value("open").build()))
                        .build());
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of());

        service.analyzeQuestion(request("orders with open-ish status", "sales_order"));

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(dynamicDataMapper).selectByQuery(anyString(), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).containsEntry("f0", "%open%");
    }

    @Test
    void llmInterpretationIsPreferredOverGeneratedText() {
        when(metaModelService.getModelDefinition("sales_order")).thenReturn(Optional.of(orderModel));
        when(chatBiLlmParser.tryParse(any(), anyString(), any(), any()))
                .thenReturn(ChatBiLlmParser.ParsedQuery.builder()
                        .modelCode("sales_order")
                        .interpretation("Counting open orders grouped by status")
                        .build());
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of());

        ChatBIResponse response = service.analyzeQuestion(request("count open orders", "sales_order"));

        assertThat(response.getInterpretation()).isEqualTo("Counting open orders grouped by status");
    }

    @Test
    void sumWithoutUsableNumericFieldDegradesToPlainSelect() {
        ModelDefinition noNumeric = ModelDefinition.builder()
                .code("note")
                .tableName("ab_dyn_note")
                .fields(List.of(FieldDefinition.builder().code("title").dataType("string").build()))
                .build();
        when(metaModelService.getModelDefinition("note")).thenReturn(Optional.of(noNumeric));
        when(chatBiLlmParser.tryParse(any(), anyString(), any(), any()))
                .thenReturn(ChatBiLlmParser.ParsedQuery.builder()
                        .modelCode("note")
                        .aggregationFunction("sum")
                        .aggregationField("nonexistent")
                        .build());
        when(dynamicDataMapper.selectByQuery(anyString(), anyMap())).thenReturn(List.of());

        ChatBIResponse response = service.analyzeQuestion(request("total of notes", "note"));

        assertThat(response.getParseMode()).isEqualTo("llm");
        assertThat(response.getSql()).doesNotContain("sum(");
        assertThat(response.getSql()).startsWith("SELECT *");
    }

    // ---- helpers ----

    private com.auraboot.framework.common.dto.PageResult<com.auraboot.framework.meta.dto.MetaModelDTO> pageOf(String... codes) {
        var page = new com.auraboot.framework.common.dto.PageResult<com.auraboot.framework.meta.dto.MetaModelDTO>();
        java.util.List<com.auraboot.framework.meta.dto.MetaModelDTO> records = new java.util.ArrayList<>();
        for (String code : codes) {
            records.add(com.auraboot.framework.meta.dto.MetaModelDTO.builder().code(code).build());
        }
        page.setRecords(records);
        return page;
    }
}
