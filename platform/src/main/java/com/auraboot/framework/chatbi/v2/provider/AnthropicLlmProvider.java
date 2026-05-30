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
 * Anthropic-backed implementation of {@link LlmProvider}. PRD 17 §4.2, §7.1, §10.
 *
 * <p>Delegates to the ACP runtime's {@link LlmProviderFactory} for credential
 * resolution + the wire call (so the chatbi-v2 module never holds an API key
 * and benefits from CloudConfig multi-tenant overrides).
 *
 * <p>Activated by {@code aura.chatbi.v2.llm-provider=anthropic}. Coexists with
 * the noop bean via {@link ConditionalOnProperty} — only one provider wins per
 * environment.
 *
 * <p>Contract guard:
 * <ul>
 *   <li>Never throws — wire / parse failures surface as
 *       {@link IntentResult#empty()} so the router can fall back to
 *       OpenAI or v1 keyword.</li>
 *   <li>Every call writes one {@code chatbi_llm_audit} row via
 *       {@link LlmAuditService} (REQUIRES_NEW, so audit failures never
 *       corrupt the answer transaction).</li>
 *   <li>{@code answerPid} / {@code conversationPid} are read from
 *       {@link AnswerCorrelation} — the orchestrator stamps them on the
 *       request thread-local before invoking translate().</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aura.chatbi.v2.llm-provider", havingValue = "anthropic")
public class AnthropicLlmProvider implements LlmProvider {

    private static final String PROVIDER_CODE = "anthropic";

    private final ObjectProvider<LlmProviderFactory> factoryProvider;
    private final LlmAuditService auditService;
    private final ChatBiPromptBuilder prompts;

    public AnthropicLlmProvider(ObjectProvider<LlmProviderFactory> factoryProvider,
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
            log.warn("LlmProviderFactory bean not available — skipping Anthropic translate");
            return IntentResult.empty();
        }

        LlmProviderFactory.ProviderResolution resolved =
                factory.resolveProvider(tenantId, PROVIDER_CODE);
        if (resolved == null) {
            log.warn("No anthropic provider config resolved for tenant {} — empty IntentResult", tenantId);
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
            log.warn("Anthropic translate failed: {}", e.getMessage());
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

        // Re-wrap with real usage (parseResponse stamps LlmUsage.zero()).
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
