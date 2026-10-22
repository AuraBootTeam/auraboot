package com.auraboot.framework.p1demo;

import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WdLeaveAiFillServiceTest {

    private LlmProviderFactory factory;
    private LlmProvider provider;
    private LlmProviderFactory.ProviderConfig config;
    private WdLeaveAiFillService service;

    @BeforeEach
    void setUp() {
        factory = mock(LlmProviderFactory.class);
        provider = mock(LlmProvider.class);
        config = mock(LlmProviderFactory.ProviderConfig.class);
        when(config.getProviderCode()).thenReturn("anthropic");
        when(config.getDefaultModel()).thenReturn("claude-haiku-4-5");
        when(config.getApiKey()).thenReturn("sk-test");
        when(config.getBaseUrl()).thenReturn("https://api.test");
        when(factory.resolveConfig(anyLong(), any())).thenReturn(config);
        when(factory.getProvider("anthropic")).thenReturn(provider);
        when(provider.estimateCost(anyString(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(0.001);
        service = new WdLeaveAiFillService(factory, new ObjectMapper());
    }

    @Test
    void noProviderConfigured_throws() {
        when(factory.resolveConfig(anyLong(), any())).thenReturn(null);
        assertThatThrownBy(() -> service.extractFields("nl", "2026-05-08", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No LLM provider configured");
    }

    @Test
    void plainJsonResponse_isParsed() throws Exception {
        stubChatResponse("""
                {"wd_req_type":"annual","wd_req_start_date":"2026-05-12",
                 "wd_req_end_date":"2026-05-13","wd_req_days":2,
                 "wd_req_reason":"family matter"}""");

        WdLeaveAiFillService.AiFillResult result = service.extractFields("nl", "2026-05-08", 1L);

        assertThat(result.fields())
                .containsEntry("wd_req_type", "annual")
                .containsEntry("wd_req_days", 2)
                .containsEntry("wd_req_reason", "family matter");
        assertThat(result.turnId()).startsWith("p1-ai-fill-");
    }

    @Test
    void markdownFencedJson_isExtracted() throws Exception {
        stubChatResponse("""
                Here is the parsed object:
                ```json
                {"wd_req_type":"sick","wd_req_days":1}
                ```
                Hope that helps.""");

        WdLeaveAiFillService.AiFillResult result = service.extractFields("nl", "2026-05-08", 1L);

        assertThat(result.fields())
                .containsEntry("wd_req_type", "sick")
                .containsEntry("wd_req_days", 1);
    }

    @Test
    void jsonWithProseSuffix_isExtractedViaBraceCounting() throws Exception {
        stubChatResponse("""
                {"wd_req_type":"personal","wd_req_days":3}

                The user requested 3 days personal leave.""");

        WdLeaveAiFillService.AiFillResult result = service.extractFields("nl", "2026-05-08", 1L);

        assertThat(result.fields())
                .containsEntry("wd_req_type", "personal")
                .containsEntry("wd_req_days", 3);
    }

    @Test
    void unparseableResponse_returnsEmptyFields() throws Exception {
        stubChatResponse("Sorry, I cannot determine the leave details from that input.");

        WdLeaveAiFillService.AiFillResult result = service.extractFields("nl", "2026-05-08", 1L);

        assertThat(result.fields()).isEmpty();
    }

    @Test
    void llmException_isWrapped() throws Exception {
        when(provider.chat(any(), anyString(), anyString())).thenThrow(new RuntimeException("network down"));

        assertThatThrownBy(() -> service.extractFields("nl", "2026-05-08", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM call failed");
    }

    @Test
    void currentDateAndNlInput_arePassedToProvider() throws Exception {
        stubChatResponse("{}");
        ArgumentCaptor<com.auraboot.framework.agent.dto.LlmChatRequest> captor =
                ArgumentCaptor.forClass(com.auraboot.framework.agent.dto.LlmChatRequest.class);

        service.extractFields("下周三家里有事请假 2 天", "2026-05-08", 1L);

        verify(provider).chat(captor.capture(), anyString(), anyString());
        Object userContent = captor.getValue().getMessages().get(0).getContent();
        assertThat(userContent).asString()
                .contains("currentDate: 2026-05-08")
                .contains("下周三家里有事请假 2 天");
    }

    private void stubChatResponse(String text) throws Exception {
        LlmChatResponse.ContentBlock block = LlmChatResponse.ContentBlock.builder()
                .type("text").text(text).build();
        LlmChatResponse response = LlmChatResponse.builder()
                .content(List.of(block))
                .inputTokens(100).outputTokens(50)
                .cacheCreationInputTokens(0).cacheReadInputTokens(0)
                .build();
        when(provider.chat(any(), anyString(), anyString())).thenReturn(response);
    }
}
