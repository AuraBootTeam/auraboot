package com.auraboot.framework.intent.service;

import com.auraboot.framework.common.util.PinnedHttpRequests;
import com.auraboot.framework.common.util.SsrfValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Default LLM client that calls an OpenAI-compatible chat completions API.
 *
 * <p>Uses pinned-IP {@link HttpClient} (P3-E DNS-rebinding hardening) so the
 * connect-time IP cannot be re-resolved to an attacker-controlled address
 * between SSRF validation and socket connect.
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @SuppressWarnings("unchecked")
    public String chat(String prompt) {
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
