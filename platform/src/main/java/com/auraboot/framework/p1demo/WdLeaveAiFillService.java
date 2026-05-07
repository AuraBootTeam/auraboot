package com.auraboot.framework.p1demo;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P1 vertical-slice service: extract leave-request field values from
 * natural-language input via the configured LLM provider.
 *
 * Validates two design claims:
 *  1) §4.3 acp_ai_annotation field set is sufficient for grounding output
 *  2) Existing LlmProviderFactory + CloudConfig is callable from a
 *     synchronous controller path (not just streaming chat)
 *
 * Contract: input is free-form Chinese / English describing a leave request;
 * output is a Map keyed by wd_leave_request field code (wd_req_type,
 * wd_req_start_date, wd_req_end_date, wd_req_days, wd_req_reason). Missing
 * fields are returned as null so the form can highlight them for the user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WdLeaveAiFillService {

    private final LlmProviderFactory llmProviderFactory;
    private final ObjectMapper objectMapper;

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*\\}");

    private static final String SYSTEM_PROMPT = """
            You extract leave-request fields from a Chinese or English natural-language
            description and return STRICT JSON. Do not add any prose or markdown.

            Output schema (omit a key if you cannot determine it):
            {
              "wd_req_type": one of ["annual","sick","personal","marriage","maternity","compassionate","other"],
              "wd_req_start_date": "YYYY-MM-DD",
              "wd_req_end_date": "YYYY-MM-DD",
              "wd_req_days": number (calendar days inclusive),
              "wd_req_reason": short reason (<= 200 chars, original language)
            }

            Resolve relative dates (today / tomorrow / next Monday / 下周三) against the
            "currentDate" hint provided in the user message. If the user gives only a
            duration without an end date, derive end_date = start_date + days - 1.
            """;

    public AiFillResult extractFields(String nlInput, String currentDate, Long tenantId) {
        LlmProviderFactory.ProviderConfig config = llmProviderFactory.resolveConfig(tenantId, null);
        if (config == null) {
            throw new IllegalStateException(
                    "No LLM provider configured. Configure one in Cloud Config "
                            + "before using AI fill (P1 vertical slice)."
            );
        }
        LlmProvider provider = llmProviderFactory.getProvider(config.getProviderCode());

        String userMessage = String.format("currentDate: %s\n\nuser said:\n%s", currentDate, nlInput);

        LlmChatRequest request = LlmChatRequest.builder()
                .model(config.getDefaultModel())
                .systemPrompt(SYSTEM_PROMPT)
                .maxTokens(512)
                .messages(List.of(
                        LlmChatRequest.Message.builder()
                                .role("user")
                                .content(userMessage)
                                .build()
                ))
                .build();

        LlmChatResponse response;
        try {
            response = provider.chat(request, config.getApiKey(), config.getBaseUrl());
        } catch (Exception e) {
            throw new IllegalStateException("LLM call failed during AI fill: " + e.getMessage(), e);
        }

        String text = extractText(response);
        Map<String, Object> fields = parseJson(text);

        int inputTokens = response.getInputTokens();
        int outputTokens = response.getOutputTokens();
        double cost = provider.estimateCost(config.getDefaultModel(), inputTokens, outputTokens,
                response.getCacheCreationInputTokens(), response.getCacheReadInputTokens());

        String turnId = "p1-ai-fill-" + UUID.randomUUID();

        return new AiFillResult(turnId, fields, (long) (inputTokens + outputTokens), cost, text);
    }

    private String extractText(LlmChatResponse response) {
        if (response.getContent() == null) return "";
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                return block.getText().trim();
            }
        }
        return "";
    }

    private Map<String, Object> parseJson(String raw) {
        Matcher m = JSON_OBJECT.matcher(raw);
        String json = m.find() ? m.group() : raw;
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse LLM response as JSON object. raw={}", raw);
            return Map.of();
        }
    }

    public record AiFillResult(String turnId, Map<String, Object> fields, long totalTokens,
                               double totalDollars, String rawResponse) {}
}
