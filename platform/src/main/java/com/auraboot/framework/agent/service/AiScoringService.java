package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.meta.dto.NamedQueryTestRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI-powered lead scoring service.
 * Uses LLM to evaluate and score CRM leads based on profile + activity data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiScoringService {

    private final LlmProviderFactory llmProviderFactory;
    private final NamedQueryService namedQueryService;
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;

    private static final int BATCH_SIZE = 10;
    private static final String SCORING_NQ = "crm_leads_for_scoring";

    /**
     * Score all unscored leads (or all leads if forceRescore=true).
     * Returns the number of leads scored.
     */
    public int scoreLeads(Long tenantId, boolean forceRescore) throws Exception {
        // 1. Resolve LLM provider
        LlmProviderFactory.ProviderConfig config = llmProviderFactory.resolveConfig(tenantId, null);
        if (config == null) {
            throw new IllegalStateException("No LLM provider configured. Please configure an LLM provider in Cloud Config.");
        }
        LlmProvider provider = llmProviderFactory.getProvider(config.getProviderCode());

        // 2. Fetch leads to score
        NamedQueryTestRequest nqRequest = new NamedQueryTestRequest();
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId.toString());
        if (!forceRescore) {
            params.put("unscoredOnly", "true");
        }
        nqRequest.setParameters(params);

        PaginationResult<Map<String, Object>> result = namedQueryService.executeQuery(SCORING_NQ, nqRequest);
        List<Map<String, Object>> leads = result.getRecords();

        if (leads == null || leads.isEmpty()) {
            log.info("No leads to score for tenant {}", tenantId);
            return 0;
        }

        log.info("Scoring {} leads for tenant {}", leads.size(), tenantId);

        // 3. Score in batches
        int scored = 0;
        for (int i = 0; i < leads.size(); i += BATCH_SIZE) {
            List<Map<String, Object>> batch = leads.subList(i, Math.min(i + BATCH_SIZE, leads.size()));
            scored += scoreBatch(provider, config, tenantId, batch);
        }

        log.info("Scored {} leads for tenant {}", scored, tenantId);
        return scored;
    }

    private int scoreBatch(LlmProvider provider, LlmProviderFactory.ProviderConfig config,
                           Long tenantId, List<Map<String, Object>> leads) throws Exception {
        // Build lead descriptions
        StringBuilder sb = new StringBuilder();
        List<String> leadPids = new ArrayList<>();
        for (int idx = 0; idx < leads.size(); idx++) {
            Map<String, Object> lead = leads.get(idx);
            String pid = String.valueOf(lead.getOrDefault("pid", ""));
            leadPids.add(pid);
            sb.append(String.format("Lead #%d (ID: %s):\n", idx + 1, pid));
            sb.append(String.format("  Company: %s\n", lead.getOrDefault("company", "N/A")));
            sb.append(String.format("  Contact: %s\n", lead.getOrDefault("contact_name", "N/A")));
            sb.append(String.format("  Source: %s\n", lead.getOrDefault("source", "N/A")));
            sb.append(String.format("  Industry: %s\n", lead.getOrDefault("industry", "N/A")));
            sb.append(String.format("  Status: %s\n", lead.getOrDefault("status", "N/A")));
            sb.append(String.format("  Activity Count: %s\n", lead.getOrDefault("activity_count", "0")));
            sb.append(String.format("  Last Activity: %s\n", lead.getOrDefault("last_activity_date", "N/A")));
            sb.append(String.format("  Requirement: %s\n", lead.getOrDefault("requirement", "N/A")));
            sb.append("\n");
        }

        String systemPrompt = """
            You are a CRM lead scoring expert. Score each lead from 0 to 100 based on:
            - Company profile and industry (20 points)
            - Contact information completeness (15 points)
            - Lead source quality (15 points)
            - Engagement level / activity count (25 points)
            - Requirement clarity and fit (25 points)

            Respond ONLY with a JSON array of objects, each with "id" (the Lead ID) and "score" (integer 0-100).
            Example: [{"id": "01ABC...", "score": 75}, {"id": "01DEF...", "score": 42}]
            No explanation, just the JSON array.
            """;

        LlmChatRequest request = LlmChatRequest.builder()
                .model(config.getDefaultModel())
                .systemPrompt(systemPrompt)
                .maxTokens(1024)
                .messages(List.of(
                        LlmChatRequest.Message.builder()
                                .role("user")
                                .content("Score the following leads:\n\n" + sb)
                                .build()
                ))
                .build();

        LlmChatResponse response = provider.chat(request, config.getApiKey(), config.getBaseUrl());

        // Parse response
        String responseText = "";
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                responseText = block.getText().trim();
            }
        }

        double cost = provider.estimateCost(config.getDefaultModel(),
                response.getInputTokens(), response.getOutputTokens());
        log.info("LLM scoring call: input={}, output={}, cost=${}",
                response.getInputTokens(), response.getOutputTokens(), cost);

        // Extract JSON array from response (may be wrapped in markdown code block)
        String jsonStr = extractJsonArray(responseText);
        if (jsonStr == null) {
            log.warn("Failed to parse LLM response as JSON array: {}", responseText);
            return 0;
        }

        List<Map<String, Object>> scores = objectMapper.readValue(jsonStr,
                new TypeReference<List<Map<String, Object>>>() {});

        // Update lead scores
        int updated = 0;
        for (Map<String, Object> scoreEntry : scores) {
            String leadId = String.valueOf(scoreEntry.get("id"));
            Object scoreObj = scoreEntry.get("score");
            if (leadId == null || scoreObj == null) continue;

            int score = scoreObj instanceof Number ? ((Number) scoreObj).intValue() : Integer.parseInt(scoreObj.toString());
            score = Math.max(0, Math.min(100, score));

            try {
                Map<String, Object> updateData = Map.of("crm_lead_score", score);
                var idEntry = resolveIdColumn(leadId);
                Map<String, Object> conditions = Map.of("tenant_id", tenantId, idEntry.getKey(), idEntry.getValue());
                dynamicDataMapper.update("mt_crm_lead", updateData, conditions);
                updated++;
            } catch (Exception e) {
                log.warn("Failed to update score for lead {}: {}", leadId, e.getMessage());
            }
        }

        return updated;
    }

    private String extractJsonArray(String text) {
        // Try direct parse
        text = text.trim();
        if (text.startsWith("[")) {
            return text;
        }
        // Extract from markdown code block
        Pattern pattern = Pattern.compile("```(?:json)?\\s*\\n?(\\[.*?])\\s*\\n?```", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // Try finding array anywhere
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private Map.Entry<String, Object> resolveIdColumn(String recordId) {
        if (recordId.length() == 26 && recordId.matches("^[0-9A-Z]+$")) {
            return Map.entry("pid", recordId);
        }
        try {
            return Map.entry("id", Long.parseLong(recordId));
        } catch (NumberFormatException e) {
            return Map.entry("pid", recordId);
        }
    }
}
