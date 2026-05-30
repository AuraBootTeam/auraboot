package com.auraboot.framework.chatbi.v2.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.chatbi.v2.service.LlmAuditService;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OpenAI-compatible (gpt-4o / o1 / o3 / DeepSeek / Qwen / GLM) implementation
 * of {@link LlmProvider}. PRD 17 §4.2, §10 fallback.
 *
 * <p>Mirrors {@link AnthropicLlmProvider} but resolves {@code providerCode=openai}
 * by default — the underlying {@code LlmProviderFactory} routes by
 * {@code apiFormat=chat_completions} so this single bean also covers DeepSeek,
 * Qwen, GLM, Moonshot etc. when the operator switches the tenant's
 * CloudConfig provider.
 *
 * <p>The {@link LlmProviderRouter} treats this provider as the secondary
 * fallback when Anthropic is wired but its circuit is open.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aura.chatbi.v2.llm-provider", havingValue = "openai")
public class OpenAiLlmProvider implements LlmProvider {

    private static final String PROVIDER_CODE = "openai";

    private final ObjectProvider<LlmProviderFactory> factoryProvider;
    private final LlmAuditService auditService;
    private final ChatBiPromptBuilder prompts;

    public OpenAiLlmProvider(ObjectProvider<LlmProviderFactory> factoryProvider,
                             LlmAuditService auditService) {
        this.factoryProvider = factoryProvider;
        this.auditService = auditService;
        this.prompts = new ChatBiPromptBuilder();
    }

    @Override
    public IntentResult translate(String nlQuery,
                                  SemanticMetaResponse catalog,
                                  ConversationContext ctx) {
        if (nlQuery == null || nlQuery.isBlank()) {
            return IntentResult.empty();
        }

        Long tenantId = currentTenantId();
        AnswerCorrelation correlation = AnswerCorrelation.current();
        long start = System.nanoTime();

        LlmProviderFactory factory = factoryProvider.getIfAvailable();
        if (factory == null) {
            log.warn("LlmProviderFactory bean not available — skipping OpenAI translate");
            return IntentResult.empty();
        }

        LlmProviderFactory.ProviderResolution resolved =
                factory.resolveProvider(tenantId, PROVIDER_CODE);
        if (resolved == null) {
            log.warn("No openai provider config resolved for tenant {} — empty IntentResult", tenantId);
            return IntentResult.empty();
        }
        String model = resolved.getConfig().getDefaultModel();

        LlmChatRequest request = LlmChatRequest.builder()
                .model(model)
                .providerCode(PROVIDER_CODE)
                .systemPrompt(prompts.buildSystemPrompt(catalog))
                .messages(prompts.buildMessages(nlQuery, ctx))
                .maxTokens(Math.max(1024, resolved.getConfig().getMaxTokens()))
                .build();

        LlmChatResponse response;
        try {
            response = resolved.getProvider().chat(
                    request,
                    resolved.getConfig().getApiKey(),
                    resolved.getConfig().getBaseUrl());
        } catch (Exception e) {
            long latencyMs = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
            LlmUsage failedUsage = new LlmUsage(model, 0, 0, 0.0d, latencyMs);
            auditService.recordFailure(tenantId, correlation.answerPid(),
                    correlation.conversationPid(), failedUsage,
                    e.getClass().getSimpleName());
            log.warn("OpenAI translate failed: {}", e.getMessage());
            return IntentResult.empty();
        }

        long latencyMs = Math.max(1L, (System.nanoTime() - start) / 1_000_000L);
        String text = firstTextBlock(response);
        IntentResult parsed = prompts.parseResponse(text);
        double costCents = 100.0d * resolved.getProvider().estimateCost(
                model,
                response.getInputTokens(),
                response.getOutputTokens(),
                response.getCacheCreationInputTokens(),
                response.getCacheReadInputTokens());
        LlmUsage usage = new LlmUsage(
                model,
                response.getInputTokens(),
                response.getOutputTokens(),
                costCents,
                latencyMs);

        auditService.recordSuccess(tenantId, correlation.answerPid(),
                correlation.conversationPid(), usage);

        return new IntentResult(
                parsed.tokens(),
                parsed.confidence(),
                parsed.needsClarification(),
                parsed.disambiguation(),
                parsed.suggestedFollowUps(),
                usage);
    }

    private static String firstTextBlock(LlmChatResponse response) {
        if (response == null) return "";
        List<LlmChatResponse.ContentBlock> blocks = response.getContent();
        if (blocks == null) return "";
        for (LlmChatResponse.ContentBlock b : blocks) {
            if ("text".equals(b.getType()) && b.getText() != null) {
                return b.getText();
            }
        }
        return "";
    }

    private static Long currentTenantId() {
        return MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
    }
}
