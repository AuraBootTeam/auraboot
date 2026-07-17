package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.AiProviderAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmProviderAccessorImplTest {

    @Test
    void resolvesCloudConfigProviderAndReturnsPluginSafeResponse() throws Exception {
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(factory.resolveConfig(7L, "qianwen")).thenReturn(LlmProviderFactory.ProviderConfig.builder()
                .providerCode("qianwen")
                .apiKey("sk-test")
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
                .defaultModel("qwen-plus")
                .maxTokens(4096)
                .build());
        when(factory.getProvider("qianwen")).thenReturn(provider);
        when(provider.chat(any(LlmChatRequest.class), eq("sk-test"), eq("https://dashscope.aliyuncs.com/compatible-mode")))
                .thenReturn(LlmChatResponse.builder()
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("{\"materialCode\":\"MAT-001\"}")
                                .build()))
                        .inputTokens(12)
                        .outputTokens(8)
                        .build());

        LlmProviderAccessorImpl accessor = new LlmProviderAccessorImpl(factory, new ObjectMapper(), 7L);
        AiProviderAccessor.ChatResponse response = accessor.chat(new AiProviderAccessor.ChatRequest(
                "bom_conversion",
                "jiejia-bom-normalizer-prod",
                "qianwen",
                "",
                "Return JSON only.",
                List.of(AiProviderAccessor.Message.user("Raw row")),
                1024,
                Map.of("source", "jiejia", "responseFormat", "json_object")
        ));

        assertEquals("qianwen", response.providerCode());
        assertEquals("qwen-plus", response.modelName());
        assertEquals("{\"materialCode\":\"MAT-001\"}", response.text());
        assertEquals(20, response.totalTokens());

        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider).chat(requestCaptor.capture(), eq("sk-test"), eq("https://dashscope.aliyuncs.com/compatible-mode"));
        assertEquals("qwen-plus", requestCaptor.getValue().getModel());
        assertEquals("Return JSON only.", requestCaptor.getValue().getSystemPrompt());
        assertEquals("Raw row", requestCaptor.getValue().getMessages().get(0).getContent());
        assertEquals(1024, requestCaptor.getValue().getMaxTokens());
        assertEquals("json_object", requestCaptor.getValue().getResponseFormat());
    }

    @Test
    void initializesTenantContextWhenCalledFromAsyncPluginThread() throws Exception {
        MetaContext.clear();
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        LlmProvider provider = mock(LlmProvider.class);
        when(factory.resolveConfig(11L, "deepseek")).thenReturn(LlmProviderFactory.ProviderConfig.builder()
                .providerCode("deepseek")
                .apiKey("sk-test")
                .baseUrl("https://api.deepseek.com")
                .defaultModel("deepseek-chat")
                .maxTokens(2048)
                .build());
        when(factory.getProvider("deepseek")).thenReturn(provider);
        when(provider.chat(any(LlmChatRequest.class), eq("sk-test"), eq("https://api.deepseek.com")))
                .thenAnswer(invocation -> {
                    assertEquals(11L, MetaContext.getCurrentTenantId());
                    return LlmChatResponse.builder()
                            .content(List.of(LlmChatResponse.ContentBlock.builder()
                                    .type("text")
                                    .text("{\"price\":1.23}")
                                    .build()))
                            .inputTokens(5)
                            .outputTokens(7)
                            .build();
                });

        LlmProviderAccessorImpl accessor = new LlmProviderAccessorImpl(factory, new ObjectMapper(), 11L);
        AiProviderAccessor.ChatResponse response = accessor.chat(new AiProviderAccessor.ChatRequest(
                "quote_deepseek_price",
                null,
                "deepseek",
                "",
                "Return JSON only.",
                List.of(AiProviderAccessor.Message.user("Suggest price")),
                512,
                Map.of()
        ));

        assertEquals("{\"price\":1.23}", response.text());
        assertEquals(false, MetaContext.exists());
    }
}
