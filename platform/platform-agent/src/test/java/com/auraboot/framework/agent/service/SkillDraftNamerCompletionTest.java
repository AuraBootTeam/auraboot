package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic completion boundary; existing PostgreSQL tests cover draft storage separately. */
class SkillDraftNamerCompletionTest {
    @ParameterizedTest
    @ValueSource(strings = {"end_turn", "max_tokens"})
    void onlyCompleteProposalCanRenameTheExistingDraft(String stopReason) throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var factory = mock(LlmProviderFactory.class);
        var provider = mock(LlmProvider.class);
        when(jdbc.queryForList(contains("FROM ab_agent_skill_draft"), eq("draft-1"), eq(7L)))
                .thenReturn(List.of(Map.of("source_pattern_hash", "hash-1", "contract_yaml", "steps: []")));
        when(jdbc.queryForList(contains("FROM ab_agent_learning_pattern"), eq("hash-1")))
                .thenReturn(List.of(Map.of("signature_json", "{}", "invocation_count", 10, "success_rate", 1.0)));
        when(factory.resolveConfig(7L, null)).thenReturn(LlmProviderFactory.ProviderConfig.builder()
                .providerCode("deepseek").apiKey("test-key").baseUrl("https://example.invalid")
                .defaultModel("test-model").build());
        when(factory.getProvider("deepseek")).thenReturn(provider);
        when(provider.chat(any(), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(LlmChatResponse.builder().stopReason(stopReason).content(List.of(
                        LlmChatResponse.ContentBlock.builder().type("text")
                                .text("{\"skill_code\":\"crm.customer_review\",\"description\":\"Review customers\"}")
                                .build())).build());
        lenient().when(jdbc.update(anyString(), eq("crm.customer_review"), eq("Review customers"), eq("draft-1"), eq(7L)))
                .thenReturn(1);
        boolean complete = "end_turn".equals(stopReason);
        assertThat(new SkillDraftNamer(jdbc, factory, new ObjectMapper()).renameDraft(7L, "draft-1"))
                .isEqualTo(complete);
        verify(jdbc, times(complete ? 1 : 0)).update(anyString(), eq("crm.customer_review"),
                eq("Review customers"), eq("draft-1"), eq(7L));
        verify(provider).chat(any(), eq("test-key"), eq("https://example.invalid"));
    }
}
