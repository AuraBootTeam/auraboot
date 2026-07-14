package com.auraboot.framework.faq;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The seam where a distilled pair becomes a candidate — and the point PII must not survive.
 *
 * <p>The model is asked not to carry personal data, but "asked not to" is not "cannot": if it
 * copies a phone number or an email through, the pair is what lands in the knowledge base and is
 * read to every future visitor. {@link PiiRedactorTest} proves the redactor works on strings; this
 * proves {@code extract} actually runs the pairs through it before returning them, against a model
 * response that deliberately carries PII. The LLM is mocked so the response is fixed and the check
 * is about the plumbing, not the model — the model's own behaviour is covered by the live IT.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("extract redacts PII out of the pairs the model returns")
class ConversationFaqExtractionRedactionTest {

    @Mock private LlmProviderFactory factory;
    @Mock private LlmProvider provider;

    private ConversationFaqExtractionService service;

    @BeforeEach
    void setUp() {
        service = new ConversationFaqExtractionService(factory);
    }

    @Test
    @DisplayName("a phone number and an email the model copied through are gone from the candidate")
    void redactsPiiTheModelCarriedThrough() throws Exception {
        LlmProviderFactory.ProviderConfig config = LlmProviderFactory.ProviderConfig.builder()
                .providerCode("deepseek").apiKey("k").baseUrl("u").defaultModel("m").maxTokens(2048).build();
        LlmProviderFactory.ProviderResolution resolution = LlmProviderFactory.ProviderResolution.builder()
                .effectiveProviderCode("deepseek").config(config).provider(provider).build();
        when(factory.resolveProvider(1L, null)).thenReturn(resolution);

        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("question", "我的手机13800138000怎么解绑账户");
        pair.put("answer", "登录后到 alice@example.com 对应的账户设置里解绑即可");
        pair.put("confidence", 0.9);

        LlmChatResponse.ContentBlock toolUse = LlmChatResponse.ContentBlock.builder()
                .type("tool_use")
                .name(ConversationFaqExtractionService.TOOL_NAME)
                .input(Map.of("faqs", List.of(pair)))
                .build();
        LlmChatResponse response = LlmChatResponse.builder().content(List.of(toolUse)).build();
        when(provider.chat(any(LlmChatRequest.class), eq("k"), eq("u"))).thenReturn(response);

        List<ExtractedFaq> out = service.extract(1L, "客户: 手机怎么解绑\n客服: 到账户设置里");

        assertThat(out).hasSize(1);
        assertThat(out.get(0).question())
                .contains("[手机号]").doesNotContain("13800138000");
        assertThat(out.get(0).answer())
                .contains("[邮箱]").doesNotContain("alice").doesNotContain("example.com");
        // The reusable part must survive — redaction removes the identifier, not the answer.
        assertThat(out.get(0).answer()).contains("账户设置");
    }
}
