package com.auraboot.framework.ai.chatbi.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChatBiLlmParser} — provider resolution, JSON reply
 * parsing, whitelist enforcement, and null-on-failure contract.
 */
@ExtendWith(MockitoExtension.class)
class ChatBiLlmParserTest {

    @Mock
    private LlmProviderFactory llmProviderFactory;
    @Mock
    private LlmProvider provider;

    private ChatBiLlmParser parser;

    private final ModelDefinition orderModel = ModelDefinition.builder()
            .code("sales_order")
            .displayName("Sales Order")
            .fields(List.of(
                    FieldDefinition.builder().code("status").dataType("string").build(),
                    FieldDefinition.builder().code("amount").dataType("decimal").build()))
            .build();

    @BeforeEach
    void setUp() {
        parser = new ChatBiLlmParser(llmProviderFactory, new ObjectMapper());
    }

    private void stubProvider(String replyText) throws Exception {
        LlmProviderFactory.ProviderConfig config = LlmProviderFactory.ProviderConfig.builder()
                .providerCode("anthropic")
                .apiKey("test-key")
                .defaultModel("test-model")
                .build();
        when(llmProviderFactory.resolveConfig(anyLong(), eq("anthropic"))).thenReturn(config);
        when(llmProviderFactory.getProvider(anyString())).thenReturn(provider);
        lenient().when(provider.chat(any(LlmChatRequest.class), anyString(), any()))
                .thenReturn(LlmChatResponse.builder()
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text(replyText)
                                .build()))
                        .build());
    }

    @Test
    void noConfiguredProviderReturnsNull() {
        when(llmProviderFactory.resolveConfig(anyLong(), eq("anthropic"))).thenReturn(null);
        when(llmProviderFactory.listConfiguredProviders(anyLong())).thenReturn(List.of());

        assertThat(parser.tryParse(1L, "count orders", orderModel, List.of())).isNull();
    }

    @Test
    void validJsonWithMarkdownFencesIsParsed() throws Exception {
        stubProvider("""
                ```json
                {"modelCode": "sales_order", "aggregation": "count", "groupByField": "status",
                 "sortOrder": "desc", "limit": 10, "trend": false,
                 "filters": [{"fieldCode": "amount", "operator": "gt", "value": 100}],
                 "interpretation": "Count orders over 100 by status"}
                ```""");

        ChatBiLlmParser.ParsedQuery parsed = parser.tryParse(1L, "count orders over 100 by status", orderModel, List.of());

        assertThat(parsed).isNotNull();
        assertThat(parsed.getModelCode()).isEqualTo("sales_order");
        assertThat(parsed.getAggregationFunction()).isEqualTo("count");
        assertThat(parsed.getGroupByField()).isEqualTo("status");
        assertThat(parsed.getLimit()).isEqualTo(10);
        assertThat(parsed.getFilters()).hasSize(1);
        assertThat(parsed.getFilters().get(0).getOperator()).isEqualTo("GT"); // normalized to upper case
        assertThat(parsed.getInterpretation()).isEqualTo("Count orders over 100 by status");
    }

    @Test
    void malformedJsonReturnsNull() throws Exception {
        stubProvider("Sure! Here is your answer: the count is 42.");
        assertThat(parser.tryParse(1L, "count orders", orderModel, List.of())).isNull();
    }

    @Test
    void providerExceptionReturnsNull() throws Exception {
        LlmProviderFactory.ProviderConfig config = LlmProviderFactory.ProviderConfig.builder()
                .providerCode("anthropic").apiKey("k").defaultModel("m").build();
        when(llmProviderFactory.resolveConfig(anyLong(), eq("anthropic"))).thenReturn(config);
        when(llmProviderFactory.getProvider(anyString())).thenReturn(provider);
        when(provider.chat(any(LlmChatRequest.class), anyString(), any()))
                .thenThrow(new RuntimeException("connection refused"));

        assertThat(parser.tryParse(1L, "count orders", orderModel, List.of())).isNull();
    }

    @Test
    void missingModelCodeReturnsNull() throws Exception {
        stubProvider("{\"aggregation\": \"count\"}");
        assertThat(parser.tryParse(1L, "count orders", orderModel, List.of())).isNull();
    }

    @Test
    void unsupportedAggregationAndOperatorAreDropped() throws Exception {
        stubProvider("""
                {"modelCode": "sales_order", "aggregation": "median",
                 "filters": [{"fieldCode": "status", "operator": "REGEX", "value": "x"},
                             {"fieldCode": "status", "operator": "EQ", "value": "open"}]}""");

        ChatBiLlmParser.ParsedQuery parsed = parser.tryParse(1L, "median order", orderModel, List.of());

        assertThat(parsed).isNotNull();
        assertThat(parsed.getAggregationFunction()).isNull(); // median not in whitelist
        assertThat(parsed.getFilters()).hasSize(1);           // REGEX dropped, EQ kept
        assertThat(parsed.getFilters().get(0).getOperator()).isEqualTo("EQ");
    }

    @Test
    void limitIsClampedToMaximum() throws Exception {
        stubProvider("{\"modelCode\": \"sales_order\", \"limit\": 999999}");

        ChatBiLlmParser.ParsedQuery parsed = parser.tryParse(1L, "all orders", orderModel, List.of());

        assertThat(parsed).isNotNull();
        assertThat(parsed.getLimit()).isEqualTo(5000);
    }

    @Test
    void promptContainsModelFieldsAndQuestion() throws Exception {
        stubProvider("{\"modelCode\": \"sales_order\"}");

        parser.tryParse(1L, "count orders", orderModel, List.of());

        ArgumentCaptor<LlmChatRequest> reqCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        org.mockito.Mockito.verify(provider).chat(reqCaptor.capture(), anyString(), any());
        LlmChatRequest sent = reqCaptor.getValue();
        assertThat(sent.getSystemPrompt()).contains("sales_order").contains("status:string").contains("amount:decimal");
        assertThat(sent.getMessages().get(0).getContent()).isEqualTo("count orders");
    }
}
