package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.PlatformAiScoreRequest;
import com.auraboot.framework.agent.dto.PlatformAiScoreResult;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.QueryBuilderReadProtection;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.service.FieldPermissionService;
import org.springframework.security.access.AccessDeniedException;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.FieldWriterGuard;
import com.auraboot.framework.meta.service.impl.ModelMutationGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


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
    private final DynamicDataService dynamicDataService;
    private final QueryBuilderReadProtection readProtection;
    private final PermissionEvaluator permissionEvaluator;
    private final FieldPermissionService fieldPermissions;
    private final DataPermissionEngine dataPermissionEngine;
    private final ObjectMapper objectMapper;

    @Override
    public PlatformAiScoreResult score(PlatformAiScoreRequest request, Long tenantId) throws Exception {
        // Scoring persists onto the target row. Reject append-only models before spending LLM
        // tokens; the per-row write loop intentionally catches ordinary record failures.
        if (!MetaContext.exists() || MetaContext.getCurrentUserId() == null || !Objects.equals(tenantId, MetaContext.getCurrentTenantId())) {
            throw new AccessDeniedException("AI scoring requires the current tenant context");
        }
        var targetModel = metaModelService.getModelDefinition(request.getModelCode())
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + request.getModelCode()));
        ModelMutationGuard.assertMutable(targetModel, "updated by AI scoring");
        FieldWriterGuard.assertFieldWriteAllowed(targetModel, request.getScoreField());

        Long memberId = MetaContext.getCurrentMemberId();
        if (memberId == null) memberId = MetaContext.getCurrentUserId();
        if (memberId == null || !permissionEvaluator.canAction(memberId, request.getModelCode(), "read")
                || !permissionEvaluator.canAction(memberId, request.getModelCode(), "update")) {
            throw new AccessDeniedException("AI scoring requires target model read and update permissions");
        }
        assertScoreFieldWritable(request.getModelCode(), request.getScoreField(), memberId);
        if (request.getContextFields() == null || request.getContextFields().isEmpty() || request.getBatchSize() <= 0) {
            throw new IllegalArgumentException("AI scoring requires context fields and a positive batch size");
        }
        String tableName = SqlSafetyUtils.requireIdentifier(
                metaModelService.getTableName(request.getModelCode()), "AI scoring table");
        Map<String, String> registered = new LinkedHashMap<>();
        for (FieldDefinition field : targetModel.getFields()) {
            if (!field.isJsonbVirtual()) {
                registered.put(field.getCode(), field.getColumnName() == null || field.getColumnName().isBlank()
                        ? field.getCode() : field.getColumnName());
            }
        }
        FieldDefinition primaryKey = metaModelService.getPrimaryKeyField(request.getModelCode());
        if (primaryKey == null) throw new IllegalArgumentException("AI scoring requires a model primary key");
        String primaryKeyCode = primaryKey.getCode();
        registered.put(primaryKeyCode, primaryKey.getColumnName() == null || primaryKey.getColumnName().isBlank()
                ? primaryKeyCode : primaryKey.getColumnName());
        registered.putIfAbsent("pid", "pid");
        Set<String> selected = new LinkedHashSet<>(List.of("pid", primaryKeyCode));
        selected.addAll(request.getContextFields());
        for (String input : request.getContextFields()) {
            if (!registered.containsKey(input) && registered.containsValue(input)) registered.put(input, input);
        }
        List<String> inputs = new ArrayList<>(selected);
        if (request.getRecordPids() == null || request.getRecordPids().isEmpty()) {
            registered.putIfAbsent("created_at", "created_at");
            inputs.add("created_at");
        }
        Set<String> neededColumns = new HashSet<>();
        for (String input : inputs) {
            if (!registered.containsKey(input)) throw new AccessDeniedException("AI scoring context fields must be registered");
            neededColumns.add(registered.get(input));
        }
        Map<String, String> columns = new LinkedHashMap<>();
        registered.forEach((code, column) -> { if (neededColumns.contains(column)) columns.put(code, column); });
        QueryBuilderReadProtection.Plan plan = readProtection.prepareComparison(
                request.getModelCode(), inputs, columns, tableName);
        String colList = selected.stream().map(code ->
                SqlSafetyUtils.requireIdentifier(columns.get(code), "AI scoring column") + " AS "
                        + SqlSafetyUtils.requireIdentifier(code, "AI scoring field"))
                .collect(java.util.stream.Collectors.joining(", "));
        StringBuilder query = new StringBuilder("SELECT ").append(colList).append(" FROM ")
                .append(tableName).append(" WHERE tenant_id = #{params.tenantId}");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tenantId", tenantId);
        if (targetModel.isSoftDelete()) query.append(" AND deleted_flag = FALSE");
        String writableRows = dataPermissionEngine.buildRowFilter(
                tenantId, request.getModelCode(), "update", MetaContext.getCurrentUserId());
        if (writableRows != null && !writableRows.isBlank()) query.append(" ").append(writableRows);
        List<String> recordPids = request.getRecordPids();
        if (recordPids != null && !recordPids.isEmpty()) {
            List<String> placeholders = new ArrayList<>();
            for (int i = 0; i < recordPids.size(); i++) {
                params.put("pid" + i, recordPids.get(i));
                placeholders.add("#{params.pid" + i + "}");
            }
            query.append(" AND pid IN (").append(String.join(",", placeholders)).append(")");
        } else {
            query.append(" ORDER BY created_at DESC LIMIT ").append(Math.max(1, Math.min(request.getLimit(), 1000)));
        }
        LlmProviderFactory.ProviderConfig config = llmProviderFactory.resolveConfig(tenantId, null);
        if (config == null) throw new IllegalStateException("No LLM provider configured. Please configure an LLM provider in Cloud Config.");
        String effectiveProviderCode = LlmProviderFactory.effectiveProviderCode(null, config);
        LlmProvider provider = llmProviderFactory.getProvider(effectiveProviderCode);

        // 4. Fetch records
        List<Map<String, Object>> records = readProtection.execute(plan, query.toString(), params);
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
                BatchResult batchResult = scoreBatch(provider, config, request, primaryKeyCode, batch);
                allScores.putAll(batchResult.scores);
                totalInputTokens += batchResult.inputTokens;
                totalOutputTokens += batchResult.outputTokens;
                failedCount += batchResult.failedCount;
            } catch (AccessDeniedException e) {
                throw e;
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

    private void assertScoreFieldWritable(String modelCode, String scoreField, Long memberId) {
        var targetModel = metaModelService.getModelDefinition(modelCode)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelCode));
        var fieldAccess = fieldPermissions.getFieldPermissions(memberId, modelCode);
        if (fieldAccess.hiddenFields().contains(scoreField)
                || !fieldAccess.editableFields().contains(scoreField)) {
            throw new AccessDeniedException("AI scoring target field is not writable");
        }
        FieldDefinition outputField = targetModel.getFields().stream()
                .filter(field -> scoreField.equals(field.getCode())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("AI scoring target field must be registered"));
        if (!outputField.isJsonbVirtual()) {
            String outputColumn = outputField.getColumnName() == null || outputField.getColumnName().isBlank()
                    ? outputField.getCode() : outputField.getColumnName();
            for (FieldDefinition alias : targetModel.getFields()) {
                String aliasColumn = alias.getColumnName() == null || alias.getColumnName().isBlank()
                        ? alias.getCode() : alias.getColumnName();
                if (!alias.isJsonbVirtual() && outputColumn.equals(aliasColumn)) {
                    if (fieldAccess.hiddenFields().contains(alias.getCode())
                            || !fieldAccess.editableFields().contains(alias.getCode())) {
                        throw new AccessDeniedException("AI scoring target column has a protected alias");
                    }
                    FieldWriterGuard.assertFieldWriteAllowed(targetModel, alias.getCode());
                }
            }
        }
    }

    private BatchResult scoreBatch(LlmProvider provider, LlmProviderFactory.ProviderConfig config,
                                   PlatformAiScoreRequest request, String primaryKeyCode,
                                   List<Map<String, Object>> records) throws Exception {
        Map<String, String> writeTargets = new LinkedHashMap<>();
        for (Map<String, Object> record : records) {
            Object pid = record.get("pid");
            Object primaryKey = record.get(primaryKeyCode);
            if (pid == null || primaryKey == null || writeTargets.putIfAbsent(pid.toString(), primaryKey.toString()) != null) {
                throw new IllegalArgumentException("AI scoring requires unique record identities");
            }
        }
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

        Set<String> returnedIds = new HashSet<>();
        for (Map<String, Object> entry : scoreEntries) {
            String pid = String.valueOf(entry.get("id"));
            if (!writeTargets.containsKey(pid) || !returnedIds.add(pid)) {
                throw new IllegalArgumentException("AI scoring response contains an unknown or duplicate record ID");
            }
        }
        // Write scores back only through the existing field, row-scope and mutation guards.
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
                Long memberId = MetaContext.getCurrentMemberId();
                if (memberId == null) memberId = MetaContext.getCurrentUserId();
                if (memberId == null || !permissionEvaluator.canAction(memberId, request.getModelCode(), "update")) {
                    throw new AccessDeniedException("AI scoring write permission revoked");
                }
                assertScoreFieldWritable(request.getModelCode(), request.getScoreField(), memberId);
                Map<String, Object> updateData = Map.of(request.getScoreField(), score);
                dynamicDataService.update(request.getModelCode(), writeTargets.get(pid), updateData);
                scores.put(pid, score);
            } catch (AccessDeniedException e) {
                throw e;
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
