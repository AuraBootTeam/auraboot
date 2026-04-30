package com.auraboot.framework.intent.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.PinnedHttpRequests;
import com.auraboot.framework.common.util.SsrfValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Default LLM client.
 *
 * <p>Two execution paths, switched on whether the caller asked for Extended
 * Thinking. Non-thinking calls keep the pre-P0-2 wire form byte-for-byte so
 * existing behaviour (and existing tests like
 * {@code GroundingServiceIntegrationTest}) is preserved:
 * <ul>
 *   <li><b>Legacy HTTP path</b> — used by {@link #chat(String)} and by
 *   {@link #chat(String, ChatOptions)} when {@link ChatOptions#thinking()}
 *   is {@code null}. Direct OpenAI-compatible HTTP call over a pinned-IP
 *   {@link HttpClient}. Identical to the pre-P0-2 behaviour.</li>
 *   <li><b>Provider path</b> — used by {@link #chat(String, ChatOptions)}
 *   when {@link ChatOptions#thinking()} is non-null. Routes through
 *   {@link LlmProvider}/{@link LlmProviderFactory} so Anthropic Extended
 *   Thinking actually reaches the wire. Capability gating stays in the
 *   provider (legacy Claude 3 / OpenAI silently drop the field).</li>
 * </ul>
 *
 * <p>Tests can {@code @MockitoBean LlmProviderFactory}: a thinking-ON call
 * captures the {@link LlmChatRequest} (and asserts {@code thinking.enabled}),
 * while a thinking-OFF call leaves the factory untouched — i.e. the
 * mockito-side contract becomes "thinking was not enabled" == "the provider
 * path was not taken", which is exactly the behaviour callers depend on.
 *
 * <p>Uses pinned-IP {@link HttpClient} (P3-E DNS-rebinding hardening) on the
 * legacy path so the connect-time IP cannot be re-resolved to an
 * attacker-controlled address between SSRF validation and socket connect.
 */
@Component
public class DefaultLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultLlmClient.class);

    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 60;

    private static final HttpClient PINNED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build();

    @Value("${intent.llm.api-url:https://api.openai.com/v1/chat/completions}")
    private String apiUrl;

    @Value("${intent.llm.api-key:}")
    private String apiKey;

    @Value("${intent.llm.model:gpt-4o}")
    private String model;

    /**
     * Optional — only required when the caller passes {@link ChatOptions} with
     * a non-null {@code thinking} config. The legacy single-arg path does not
     * need it, so wiring stays optional to keep backwards compatibility for
     * environments that only use OpenAI-compatible HTTP.
     */
    @Autowired(required = false)
    private LlmProviderFactory llmProviderFactory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Legacy 1-arg path — explicit override (instead of inheriting the
     * interface default) so that existing callers like
     * {@code IntentAnalyzerService} keep the pre-P0-2 OpenAI-compatible HTTP
     * wire form even when the new 2-arg overload is in scope.
     */
    @Override
    public String chat(String prompt) {
        return chatViaOpenAiHttp(prompt);
    }

    /**
     * 2-arg path — switches on {@link ChatOptions#thinking()}:
     * non-null routes through {@link LlmProvider} (so Extended Thinking can
     * reach the wire); null falls back to the legacy HTTP path so callers
     * who only want a model/maxTokens override don't get re-routed onto a
     * different wire format.
     */
    @Override
    public String chat(String prompt, ChatOptions options) {
        ChatOptions effective = (options != null) ? options : ChatOptions.defaults();
        if (effective.thinking() == null) {
            return chatViaOpenAiHttp(prompt);
        }
        return chatViaProvider(prompt, effective);
    }

    /**
     * Provider-routed call — translates {@link ChatOptions} into a
     * {@link LlmChatRequest} and dispatches through {@link LlmProvider}.
     */
    private String chatViaProvider(String prompt, ChatOptions options) {
        if (llmProviderFactory == null) {
            throw new IllegalStateException(
                    "LlmProviderFactory is not wired — the 2-arg chat(prompt, options) "
                            + "path requires it. Use chat(prompt) for the legacy HTTP path.");
        }

        Long tenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        // Auto-resolve provider (factory picks first configured one when null).
        LlmProviderFactory.ProviderConfig config = llmProviderFactory.resolveConfig(tenantId, null);
        if (config == null) {
            throw new IllegalStateException(
                    "No LLM provider configured for tenant " + tenantId);
        }
        LlmProvider provider = llmProviderFactory.getProvider(config.getProviderCode());
        if (provider == null) {
            throw new IllegalStateException(
                    "LlmProvider not found for code: " + config.getProviderCode());
        }

        String resolvedModel = (options.modelOverride() != null && !options.modelOverride().isBlank())
                ? options.modelOverride()
                : config.getDefaultModel();
        int resolvedMaxTokens = (options.maxTokensOverride() != null && options.maxTokensOverride() > 0)
                ? options.maxTokensOverride()
                : (config.getMaxTokens() > 0 ? config.getMaxTokens() : 4096);

        LlmChatRequest request = LlmChatRequest.builder()
                .model(resolvedModel)
                .providerCode(config.getProviderCode())
                .maxTokens(resolvedMaxTokens)
                .thinking(options.thinking())
                .messages(List.of(
                        LlmChatRequest.Message.builder()
                                .role("user")
                                .content(prompt)
                                .build()
                ))
                .build();

        LlmChatResponse response;
        try {
            response = provider.chat(request, config.getApiKey(), config.getBaseUrl());
        } catch (RuntimeException re) {
            log.error("LlmProvider call failed: {}", re.getMessage(), re);
            throw re;
        } catch (Exception e) {
            log.error("LlmProvider call failed: {}", e.getMessage(), e);
            throw new RuntimeException("LlmProvider call failed: " + e.getMessage(), e);
        }

        if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
            throw new RuntimeException("Empty response from LlmProvider");
        }

        // Concatenate all text blocks; thinking blocks are intentionally
        // dropped here — IntentParser only consumes the user-visible answer.
        StringBuilder text = new StringBuilder();
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                text.append(block.getText());
            }
        }
        return text.toString();
    }

    /**
     * Legacy direct-HTTP path — preserves pre-P0-2 wire form byte-for-byte.
     */
    @SuppressWarnings("unchecked")
    private String chatViaOpenAiHttp(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("LLM API key is not configured (intent.llm.api-key)");
        }

        // Validate URL + pin the resolved IP (P3-E #1).
        SsrfValidator.ValidatedTarget target = SsrfValidator.validate(apiUrl);
        if (target == null) {
            throw new IllegalStateException("LLM API URL could not be resolved: " + apiUrl);
        }

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.2
        );

        try {
            String bodyJson = objectMapper.writeValueAsString(body);

            HttpRequest request = PinnedHttpRequests.newPinnedRequestBuilder(target)
                    .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = PINNED_HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw new RuntimeException("LLM API returned error status "
                        + response.statusCode() + ": " + response.body());
            }

            Map<String, Object> responseBody = objectMapper.readValue(response.body(), Map.class);
            if (responseBody == null) {
                throw new RuntimeException("Empty response from LLM API");
            }

            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("No choices in LLM response");
            }

            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return (String) message.get("content");
        } catch (RuntimeException re) {
            log.error("LLM API call failed: {}", re.getMessage(), re);
            throw re;
        } catch (Exception e) {
            log.error("LLM API call failed: {}", e.getMessage(), e);
            throw new RuntimeException("LLM API call failed: " + e.getMessage(), e);
        }
    }
}
