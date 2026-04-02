package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.PlatformAiScoreRequest;
import com.auraboot.framework.agent.dto.PlatformAiScoreResult;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.stream.Collectors.joining;

/**
 * Default implementation of {@link PlatformAiScoringService}.
 *
 * <p>Scores records of any DSL model using the configured LLM provider and writes
 * the result (0-100) back to the specified score field.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformAiScoringServiceImpl implements PlatformAiScoringService {

    private final LlmProviderFactory llmProviderFactory;
    private final MetaModelService metaModelService;
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;

    @Override
    public PlatformAiScoreResult score(PlatformAiScoreRequest request, Long tenantId) throws Exception {
        // 1. Resolve LLM provider
        LlmProviderFactory.ProviderConfig config = llmProviderFactory.resolveConfig(tenantId, null);
        if (config == null) {
            throw new IllegalStateException(
                    "No LLM provider configured. Please configure an LLM provider in Cloud Config.");
        }
        LlmProvider provider = llmProviderFactory.getProvider(config.getProviderCode());

        // 2. Resolve table name
        String tableName = metaModelService.getTableName(request.getModelCode());
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException(
                    "Model not found or has no table: " + request.getModelCode());
        }

        // 3. Build query SQL
        Set<String> cols = new LinkedHashSet<>();
        cols.add("pid");
        cols.addAll(request.getContextFields());
        String colList = String.join(", ", cols);

        String sql;
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);

        List<String> recordPids = request.getRecordPids();
        if (recordPids != null && !recordPids.isEmpty()) {
            String pidList = recordPids.stream()
                    .map(p -> "'" + p + "'")
                    .collect(joining(","));
            sql = "SELECT " + colList + " FROM " + tableName
                    + " WHERE tenant_id = #{tenantId}"
                    + " AND pid IN (" + pidList + ")";
        } else {
            int lim = Math.max(1, Math.min(request.getLimit(), 1000));
            sql = "SELECT " + colList + " FROM " + tableName
                    + " WHERE tenant_id = #{tenantId}"
                    + " ORDER BY created_at DESC LIMIT " + lim;
        }

        // 4. Fetch records
        List<Map<String, Object>> records = dynamicDataMapper.selectByQuery(sql, params);
        if (records == null || records.isEmpty()) {
            log.info("No records to score for model={} tenant={}", request.getModelCode(), tenantId);
            return PlatformAiScoreResult.builder()
                    .modelCode(request.getModelCode())
                    .scoreField(request.getScoreField())
                    .scoredCount(0)
                    .failedCount(0)
                    .scores(Collections.emptyMap())
                    .totalInputTokens(0)
                    .totalOutputTokens(0)
                    .build();
        }

        log.info("Scoring {} records for model={} tenant={}", records.size(), request.getModelCode(), tenantId);

        // 5. Score in batches
        int effectiveBatchSize = Math.min(request.getBatchSize(), 20);
        Map<String, Integer> allScores = new LinkedHashMap<>();
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int failedCount = 0;

        for (int i = 0; i < records.size(); i += effectiveBatchSize) {
            List<Map<String, Object>> batch = records.subList(i, Math.min(i + effectiveBatchSize, records.size()));
            try {
                BatchResult batchResult = scoreBatch(provider, config, request, tenantId, tableName, batch);
                allScores.putAll(batchResult.scores);
                totalInputTokens += batchResult.inputTokens;
                totalOutputTokens += batchResult.outputTokens;
                failedCount += batchResult.failedCount;
            } catch (Exception e) {
                log.warn("Batch scoring failed for model={} batch starting at {}: {}",
                        request.getModelCode(), i, e.getMessage());
                failedCount += batch.size();
            }
        }

        return PlatformAiScoreResult.builder()
                .modelCode(request.getModelCode())
                .scoreField(request.getScoreField())
                .scoredCount(allScores.size())
                .failedCount(failedCount)
                .scores(allScores)
                .totalInputTokens(totalInputTokens)
                .totalOutputTokens(totalOutputTokens)
                .build();
    }

    private BatchResult scoreBatch(LlmProvider provider, LlmProviderFactory.ProviderConfig config,
                                   PlatformAiScoreRequest request, Long tenantId,
                                   String tableName, List<Map<String, Object>> records) throws Exception {
        // Build record descriptions
        StringBuilder sb = new StringBuilder();
        for (int idx = 0; idx < records.size(); idx++) {
            Map<String, Object> record = records.get(idx);
            String pid = String.valueOf(record.getOrDefault("pid", ""));
            sb.append(String.format("Record #%d (ID: %s):\n", idx + 1, pid));
            for (String field : request.getContextFields()) {
                sb.append(String.format("  %s: %s\n", field, record.getOrDefault(field, "N/A")));
            }
            sb.append("\n");
        }

        // Build system prompt with scoring dimensions
        StringBuilder dimensionsText = new StringBuilder();
        for (PlatformAiScoreRequest.ScoringDimension dim : request.getScoringDimensions()) {
            dimensionsText.append(String.format("- %s (%d points): %s\n",
                    dim.getFieldCode(), dim.getWeight(), dim.getDescription()));
        }

        String systemPrompt = String.format("""
                You are an AI scoring expert. Score each record from 0 to 100 based on the following dimensions:
                %s
                Respond ONLY with a JSON array of objects, each with "id" (the Record ID) and "score" (integer 0-100).
                Example: [{"id": "01ABC...", "score": 75}, {"id": "01DEF...", "score": 42}]
                No explanation, just the JSON array.
                """, dimensionsText);

        LlmChatRequest llmRequest = LlmChatRequest.builder()
                .model(config.getDefaultModel())
                .systemPrompt(systemPrompt)
                .maxTokens(1024)
                .messages(List.of(
                        LlmChatRequest.Message.builder()
                                .role("user")
                                .content("Score the following records:\n\n" + sb)
                                .build()
                ))
                .build();

        LlmChatResponse response = provider.chat(llmRequest, config.getApiKey(), config.getBaseUrl());

        // Extract text from response
        String responseText = "";
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                responseText = block.getText().trim();
            }
        }

        log.info("LLM scoring batch: input={}, output={} for model={}",
                response.getInputTokens(), response.getOutputTokens(), request.getModelCode());

        // Parse JSON array
        String jsonStr = extractJsonArray(responseText);
        if (jsonStr == null) {
            log.warn("Failed to parse LLM response as JSON array for model={}: {}",
                    request.getModelCode(), responseText);
            return new BatchResult(Collections.emptyMap(), response.getInputTokens(), response.getOutputTokens(), records.size());
        }

        List<Map<String, Object>> scoreEntries = objectMapper.readValue(jsonStr,
                new TypeReference<List<Map<String, Object>>>() {});

        // Write scores back
        Map<String, Integer> scores = new LinkedHashMap<>();
        int failedCount = 0;
        for (Map<String, Object> entry : scoreEntries) {
            String pid = String.valueOf(entry.get("id"));
            Object scoreObj = entry.get("score");
            if (pid == null || "null".equals(pid) || scoreObj == null) continue;

            int score = scoreObj instanceof Number ? ((Number) scoreObj).intValue()
                    : Integer.parseInt(scoreObj.toString());
            score = Math.max(0, Math.min(100, score));

            try {
                Map<String, Object> updateData = Map.of(request.getScoreField(), score);
                Map<String, Object> conditions = Map.of("tenant_id", tenantId, "pid", pid);
                dynamicDataMapper.update(tableName, updateData, conditions);
                scores.put(pid, score);
            } catch (Exception e) {
                log.warn("Failed to write score for pid={} model={}: {}", pid, request.getModelCode(), e.getMessage());
                failedCount++;
            }
        }

        return new BatchResult(scores, response.getInputTokens(), response.getOutputTokens(), failedCount);
    }

    private String extractJsonArray(String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.startsWith("[")) {
            return text;
        }
        Pattern pattern = Pattern.compile("```(?:json)?\\s*\\n?(\\[.*?])\\s*\\n?```", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    /** Internal batch result carrier. */
    private record BatchResult(
            Map<String, Integer> scores,
            int inputTokens,
            int outputTokens,
            int failedCount
    ) {}
}
