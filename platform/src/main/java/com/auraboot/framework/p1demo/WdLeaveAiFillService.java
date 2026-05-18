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

    private static final Pattern MARKDOWN_FENCED_JSON =
            Pattern.compile("```(?:json)?\\s*\\n?(\\{[\\s\\S]*?\\})\\s*\\n?```", Pattern.DOTALL);

    private static final String SYSTEM_PROMPT = """
            You extract leave-request fields from a Chinese or English natural-language
            description and return STRICT JSON. Do not add any prose or markdown.

            Output schema (omit a key if you cannot determine it):
            {
              "wd_req_type": one of ["annual","sick","personal","comp"],
              "wd_req_start_date": "YYYY-MM-DD",
              "wd_req_end_date": "YYYY-MM-DD",
              "wd_req_days": number (calendar days inclusive),
              "wd_req_reason": short reason (<= 200 chars, original language)
            }

            Field-value semantics:
              annual = 年假 / paid time off
              sick = 病假 / sick leave
              personal = 事假 / personal unpaid leave
              comp = 调休 / compensatory time-off

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
        String effectiveProviderCode = LlmProviderFactory.effectiveProviderCode(null, config);
        LlmProvider provider = llmProviderFactory.getProvider(effectiveProviderCode);

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
        String json = extractJsonObject(raw);
        if (json == null) {
            log.warn("Failed to find JSON object in LLM response. raw={}", raw);
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse LLM response as JSON object. extracted={}", json);
            return Map.of();
        }
    }

    /**
     * Extract a single JSON object from LLM output. Tries three strategies:
     *   1) The whole response is already a JSON object
     *   2) Markdown-fenced ```json ... ``` block
     *   3) Brace-counting scan to skip any prose / suffix
     * Returns null if no balanced object is found.
     */
    private String extractJsonObject(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.startsWith("{") && text.endsWith("}")) {
            return text;
        }
        Matcher fenced = MARKDOWN_FENCED_JSON.matcher(text);
        if (fenced.find()) {
            return fenced.group(1);
        }
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }

    public record AiFillResult(String turnId, Map<String, Object> fields, long totalTokens,
                               double totalDollars, String rawResponse) {}
}
