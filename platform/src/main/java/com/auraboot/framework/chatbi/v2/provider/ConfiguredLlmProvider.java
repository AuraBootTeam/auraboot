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
 * Default ChatBI adapter backed by the platform provider registry.
 *
 * <p>The active provider, wire adapter, endpoint and model are resolved from
 * tenant CloudConfig. ChatBI owns no vendor fallback list or model inference.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "aura.chatbi.v2.llm-provider",
        havingValue = "configured",
        matchIfMissing = true)
public class ConfiguredLlmProvider implements LlmProvider {

    private final ObjectProvider<LlmProviderFactory> factoryProvider;
    private final LlmAuditService auditService;
    private final ChatBiPromptBuilder prompts = new ChatBiPromptBuilder();

    public ConfiguredLlmProvider(
            ObjectProvider<LlmProviderFactory> factoryProvider,
            LlmAuditService auditService) {
        this.factoryProvider = factoryProvider;
        this.auditService = auditService;
    }

    @Override
    public String routingKey() {
        return "tenant-configured";
    }

    @Override
    public IntentResult translate(
            String nlQuery,
            SemanticMetaResponse catalog,
            ConversationContext ctx) {
        if (nlQuery == null || nlQuery.isBlank()) {
            return IntentResult.empty();
        }
        Long tenantId = MetaContext.exists()
                ? MetaContext.getCurrentTenantId()
                : null;
        LlmProviderFactory factory = factoryProvider.getIfAvailable();
        if (factory == null) {
            log.warn("Platform LLM registry is unavailable; ChatBI will use catalog parsing");
            return IntentResult.empty();
        }
        LlmProviderFactory.ProviderResolution resolved =
                factory.resolveProvider(tenantId, null);
        if (resolved == null || resolved.getConfig() == null
                || resolved.getProvider() == null) {
            log.warn("No tenant LLM profile is configured; ChatBI will use catalog parsing");
            return IntentResult.empty();
        }

        String model = resolved.getConfig().getDefaultModel();
        LlmChatRequest request = LlmChatRequest.builder()
                .model(model)
                .providerCode(resolved.getEffectiveProviderCode())
                .systemPrompt(prompts.buildSystemPrompt(catalog))
                .messages(prompts.buildMessages(nlQuery, ctx))
                .maxTokens(Math.max(1024, resolved.getConfig().getMaxTokens()))
                .build();
        AnswerCorrelation correlation = AnswerCorrelation.current();
        long started = System.nanoTime();
        try {
            LlmChatResponse response = resolved.getProvider().chat(
                    request,
                    resolved.getConfig().getApiKey(),
                    resolved.getConfig().getBaseUrl());
            long latencyMs = elapsedMillis(started);
            String text = firstTextBlock(response);
            IntentResult parsed = prompts.parseResponse(text);
            double costCents = 100.0d * resolved.getProvider().estimateCost(
                    model,
                    response == null ? 0 : response.getInputTokens(),
                    response == null ? 0 : response.getOutputTokens(),
                    response == null ? 0 : response.getCacheCreationInputTokens(),
                    response == null ? 0 : response.getCacheReadInputTokens());
            LlmUsage usage = new LlmUsage(
                    model,
                    response == null ? 0 : response.getInputTokens(),
                    response == null ? 0 : response.getOutputTokens(),
                    costCents,
                    latencyMs);
            auditService.recordSuccess(
                    tenantId,
                    correlation.answerPid(),
                    correlation.conversationPid(),
                    usage);
            return new IntentResult(
                    parsed.tokens(),
                    parsed.confidence(),
                    parsed.needsClarification(),
                    parsed.disambiguation(),
                    parsed.suggestedFollowUps(),
                    usage);
        } catch (Exception e) {
            LlmUsage usage = new LlmUsage(model, 0, 0, 0.0d, elapsedMillis(started));
            auditService.recordFailure(
                    tenantId,
                    correlation.answerPid(),
                    correlation.conversationPid(),
                    usage,
                    e.getClass().getSimpleName());
            log.warn("Configured ChatBI provider failed: {}", e.getMessage());
            return IntentResult.empty();
        }
    }

    private static long elapsedMillis(long started) {
        return Math.max(1L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static String firstTextBlock(LlmChatResponse response) {
        if (response == null) {
            return "";
        }
        List<LlmChatResponse.ContentBlock> blocks = response.getContent();
        if (blocks == null) {
            return "";
        }
        for (LlmChatResponse.ContentBlock block : blocks) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                return block.getText();
            }
        }
        return "";
    }
}
