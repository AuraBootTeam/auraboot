package com.auraboot.framework.ai.search.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.ai.search.dto.AiSearchRequest;
import com.auraboot.framework.ai.search.dto.AiSearchResult;
import com.auraboot.framework.ai.search.dto.ParsedFilter;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.dto.SortField;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AI-powered natural language search implementation.
 * <p>
 * Strategy:
 * <ol>
 *   <li>If an LLM provider is configured, send the query to LLM with a structured prompt
 *       that includes available models and their fields. The LLM responds with modelCode,
 *       filters, and sort instructions in JSON.</li>
 *   <li>If no LLM is available, fall back to cross-model keyword search: iterate over
 *       published models and run keyword search on each, collecting the first matches.</li>
 * </ol>
 *
 * @author AuraBoot Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSearchServiceImpl implements AiSearchService {

    private static final int MAX_MODELS_IN_PROMPT = 50;
    private static final int MAX_FIELDS_PER_MODEL = 30;
    private static final Set<String> SKIP_MODEL_TYPES = Set.of("view", "meta");

    private final MetaModelMapper metaModelMapper;
    private final MetaModelService metaModelService;
    private final DynamicDataService dynamicDataService;
    private final LlmProviderFactory llmProviderFactory;
    private final ObjectMapper objectMapper;

    @Override
    public AiSearchResult search(AiSearchRequest request) {
        String query = request.getQuery();
        if (query == null || query.isBlank()) {
            return AiSearchResult.builder()
                    .records(Collections.emptyList())
                    .totalCount(0)
                    .llmUsed(false)
                    .explanation("Empty query")
                    .build();
        }

        Long tenantId = MetaContext.getCurrentTenantId();

        // Try LLM-powered search first
        if (isLlmAvailable(tenantId)) {
            try {
                return llmSearch(tenantId, request);
            } catch (Exception e) {
                log.warn("LLM search failed, falling back to keyword search: {}", e.getMessage());
            }
        }

        // Fallback: cross-model keyword search
        return keywordFallbackSearch(request);
    }

    // =========================================================================
    // LLM-powered search
    // =========================================================================

    private AiSearchResult llmSearch(Long tenantId, AiSearchRequest request) throws Exception {
        // 1. Build model catalog for the prompt
        List<Model> models = metaModelMapper.findCurrentByTenant();
        Map<String, ModelSummary> catalog = buildModelCatalog(models);

        if (catalog.isEmpty()) {
            return keywordFallbackSearch(request);
        }

        // 2. Call LLM
        String systemPrompt = buildSystemPrompt(catalog);
        String userPrompt = request.getQuery();

        LlmProviderFactory.ProviderConfig config = resolveFirstAvailableConfig(tenantId);
        if (config == null) {
            return keywordFallbackSearch(request);
        }

        LlmProvider provider = llmProviderFactory.getProvider(config.getProviderCode());
        LlmChatRequest chatRequest = LlmChatRequest.builder()
                .model(config.getDefaultModel())
                .systemPrompt(systemPrompt)
                .messages(List.of(LlmChatRequest.Message.builder()
                        .role("user")
                        .content(userPrompt)
                        .build()))
                .maxTokens(1024)
                .build();

        LlmChatResponse response = provider.chat(chatRequest, config.getApiKey(), config.getBaseUrl());
        String responseText = extractTextContent(response);
        if (responseText == null || responseText.isBlank()) {
            log.warn("LLM returned empty response for AI search");
            return keywordFallbackSearch(request);
        }

        // 3. Parse LLM response
        String json = stripMarkdownFences(responseText);
        Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

        String modelCode = (String) parsed.get("modelCode");
        if (modelCode == null || modelCode.isBlank()) {
            log.warn("LLM did not identify a model from query: {}", request.getQuery());
            return keywordFallbackSearch(request);
        }

        // 4. Build query conditions from parsed filters
        List<ParsedFilter> parsedFilters = new ArrayList<>();
        List<QueryCondition> conditions = new ArrayList<>();

        Object filtersObj = parsed.get("filters");
        if (filtersObj instanceof List<?> filtersList) {
            for (Object item : filtersList) {
                if (item instanceof Map<?, ?> filterMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fm = (Map<String, Object>) filterMap;
                    String fieldName = (String) fm.get("fieldName");
                    String operator = (String) fm.get("operator");
                    Object value = fm.get("value");

                    if (fieldName != null && operator != null) {
                        ParsedFilter pf = ParsedFilter.builder()
                                .fieldName(fieldName)
                                .operator(operator.toUpperCase())
                                .value(value)
                                .displayValue(fieldName + " " + operator + " " + value)
                                .build();
                        parsedFilters.add(pf);

                        QueryCondition.Operator op = safeParseOperator(operator);
                        if (op != null) {
                            conditions.add(QueryCondition.builder()
                                    .fieldName(fieldName)
                                    .operator(op)
                                    .value(value)
                                    .build());
                        }
                    }
                }
            }
        }

        // 5. Build sort
        String sortFieldStr = (String) parsed.get("sortField");
        String sortOrderStr = (String) parsed.get("sortOrder");
        List<SortField> sortFields = new ArrayList<>();
        if (sortFieldStr != null && !sortFieldStr.isBlank()) {
            SortField.SortDirection dir = "asc".equalsIgnoreCase(sortOrderStr)
                    ? SortField.SortDirection.ASC : SortField.SortDirection.DESC;
            sortFields.add(SortField.builder().fieldName(sortFieldStr).direction(dir).build());
        }

        // 6. Execute query
        int maxResults = Math.min(Math.max(request.getMaxResults(), 1), 100);
        DynamicQueryRequest queryRequest = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(maxResults)
                .conditions(conditions.isEmpty() ? null : conditions)
                .sortFields(sortFields.isEmpty() ? null : sortFields)
                .build();

        PaginationResult<Map<String, Object>> result;
        try {
            result = dynamicDataService.list(modelCode, queryRequest);
        } catch (Exception e) {
            log.warn("Dynamic query failed for modelCode={}: {}", modelCode, e.getMessage());
            return AiSearchResult.builder()
                    .modelCode(modelCode)
                    .parsedFilters(parsedFilters)
                    .records(Collections.emptyList())
                    .totalCount(0)
                    .llmUsed(true)
                    .explanation("Query parsed but execution failed: " + e.getMessage())
                    .build();
        }

        // 7. Build explanation
        String explanation = (String) parsed.get("explanation");
        if (explanation == null || explanation.isBlank()) {
            explanation = "Searched " + modelCode + " with " + parsedFilters.size() + " filter(s)";
        }

        // Resolve model label
        String modelLabel = modelCode;
        ModelSummary summary = catalog.get(modelCode);
        if (summary != null && summary.label != null) {
            modelLabel = summary.label;
        }

        return AiSearchResult.builder()
                .modelCode(modelCode)
                .modelLabel(modelLabel)
                .parsedFilters(parsedFilters)
                .records(result.getRecords() != null ? result.getRecords() : Collections.emptyList())
                .totalCount(result.getTotal() != null ? result.getTotal() : 0L)
                .llmUsed(true)
                .explanation(explanation)
                .sortField(sortFieldStr)
                .sortOrder(sortOrderStr)
                .build();
    }

    // =========================================================================
    // Keyword fallback search
    // =========================================================================

    private AiSearchResult keywordFallbackSearch(AiSearchRequest request) {
        String keyword = request.getQuery();
        int maxResults = Math.min(Math.max(request.getMaxResults(), 1), 100);

        List<Model> models = metaModelMapper.findCurrentByTenant();
        List<Map<String, Object>> allRecords = new ArrayList<>();
        String matchedModelCode = null;
        String matchedModelLabel = null;
        long totalCount = 0;

        for (Model model : models) {
            if (allRecords.size() >= maxResults) break;

            String modelType = model.getModelType();
            if (modelType != null && SKIP_MODEL_TYPES.contains(modelType.toLowerCase())) {
                continue;
            }

            String code = model.getCode();
            try {
                DynamicQueryRequest queryRequest = DynamicQueryRequest.builder()
                        .pageNum(1)
                        .pageSize(Math.min(maxResults - allRecords.size(), 10))
                        .keyword(keyword)
                        .build();

                PaginationResult<Map<String, Object>> result = dynamicDataService.list(code, queryRequest);
                if (result.getRecords() != null && !result.getRecords().isEmpty()) {
                    if (matchedModelCode == null) {
                        matchedModelCode = code;
                        matchedModelLabel = model.getDisplayName() != null ? model.getDisplayName() : code;
                    }
                    // Tag each record with its source model
                    for (Map<String, Object> record : result.getRecords()) {
                        Map<String, Object> tagged = new LinkedHashMap<>(record);
                        tagged.put("_modelCode", code);
                        tagged.put("_modelLabel", model.getDisplayName() != null ? model.getDisplayName() : code);
                        allRecords.add(tagged);
                    }
                    totalCount += (result.getTotal() != null ? result.getTotal() : 0);
                }
            } catch (Exception e) {
                log.debug("Keyword search skipped model {}: {}", code, e.getMessage());
            }
        }

        return AiSearchResult.builder()
                .modelCode(matchedModelCode)
                .modelLabel(matchedModelLabel)
                .parsedFilters(Collections.emptyList())
                .records(allRecords)
                .totalCount(totalCount)
                .llmUsed(false)
                .explanation("Keyword search across all models for: " + keyword)
                .build();
    }

    // =========================================================================
    // Prompt building
    // =========================================================================

    private String buildSystemPrompt(Map<String, ModelSummary> catalog) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("""
                You are a search query parser for an enterprise business management system.
                Given a natural language query, parse it into structured search parameters.

                IMPORTANT RULES:
                1. Identify which object (model) the user wants to search.
                2. Extract filter conditions from the query.
                3. Determine sort order if mentioned.
                4. Provide a brief explanation of your interpretation.

                Available objects and their fields:

                """);

        int modelCount = 0;
        for (Map.Entry<String, ModelSummary> entry : catalog.entrySet()) {
            if (modelCount >= MAX_MODELS_IN_PROMPT) break;
            ModelSummary ms = entry.getValue();
            sb.append("- ").append(entry.getKey());
            if (ms.label != null) {
                sb.append(" (").append(ms.label).append(")");
            }
            sb.append(": ");
            sb.append(String.join(", ", ms.fields));
            sb.append("\n");
            modelCount++;
        }

        sb.append("""

                Respond with ONLY a JSON object (no markdown fences, no explanation outside JSON):
                {
                  "modelCode": "the_object_code",
                  "filters": [
                    {"fieldName": "field_code", "operator": "EQ|NE|GT|GE|LT|LE|LIKE|IN", "value": "value"}
                  ],
                  "sortField": "field_code_or_null",
                  "sortOrder": "ASC|DESC|null",
                  "explanation": "Brief explanation of what you understood from the query"
                }

                If you cannot determine the model, return: {"modelCode": null, "filters": [], "explanation": "reason"}
                For text search / partial match, use operator "LIKE".
                For status filters, use "EQ".
                For date comparisons, use GT/LT/GE/LE with ISO format dates.
                """);

        return sb.toString();
    }

    private Map<String, ModelSummary> buildModelCatalog(List<Model> models) {
        Map<String, ModelSummary> catalog = new LinkedHashMap<>();

        for (Model model : models) {
            String code = model.getCode();
            String modelType = model.getModelType();
            if (modelType != null && SKIP_MODEL_TYPES.contains(modelType.toLowerCase())) {
                continue;
            }

            try {
                Optional<ModelDefinition> defOpt = metaModelService.getModelDefinition(code);
                if (defOpt.isEmpty()) continue;

                ModelDefinition def = defOpt.get();
                List<String> fieldNames = new ArrayList<>();
                if (def.getFields() != null) {
                    int count = 0;
                    for (FieldDefinition fd : def.getFields()) {
                        if (count >= MAX_FIELDS_PER_MODEL) break;
                        String entry = fd.getCode();
                        if (fd.getDataType() != null) {
                            entry += "(" + fd.getDataType() + ")";
                        }
                        fieldNames.add(entry);
                        count++;
                    }
                }

                ModelSummary summary = new ModelSummary();
                summary.label = model.getDisplayName();
                summary.fields = fieldNames;
                catalog.put(code, summary);
            } catch (Exception e) {
                log.debug("Skipping model {} for AI search catalog: {}", code, e.getMessage());
            }
        }

        return catalog;
    }

    // =========================================================================
    // LLM helpers (same pattern as AiTranslationService)
    // =========================================================================

    private boolean isLlmAvailable(Long tenantId) {
        try {
            LlmProviderFactory.ProviderConfig config = llmProviderFactory.resolveConfig(tenantId, "anthropic");
            if (config != null && config.getApiKey() != null && !config.getApiKey().isBlank()) {
                return true;
            }
            List<LlmProviderFactory.ProviderInfo> providers = llmProviderFactory.listConfiguredProviders(tenantId);
            return !providers.isEmpty();
        } catch (Exception e) {
            log.debug("LLM availability check failed: {}", e.getMessage());
            return false;
        }
    }

    private LlmProviderFactory.ProviderConfig resolveFirstAvailableConfig(Long tenantId) {
        LlmProviderFactory.ProviderConfig config = llmProviderFactory.resolveConfig(tenantId, "anthropic");
        if (config != null) return config;

        List<LlmProviderFactory.ProviderInfo> providers = llmProviderFactory.listConfiguredProviders(tenantId);
        for (LlmProviderFactory.ProviderInfo info : providers) {
            LlmProviderFactory.ProviderConfig c = llmProviderFactory.resolveConfig(tenantId, info.getProviderCode());
            if (c != null) return c;
        }
        return null;
    }

    private String extractTextContent(LlmChatResponse response) {
        if (response == null || response.getContent() == null) return null;
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                return block.getText();
            }
        }
        return null;
    }

    private String stripMarkdownFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).trim();
            }
        }
        return trimmed;
    }

    private QueryCondition.Operator safeParseOperator(String operator) {
        if (operator == null) return null;
        try {
            return QueryCondition.Operator.fromCode(operator);
        } catch (Exception e) {
            log.debug("Unknown operator from LLM: {}", operator);
            // Map common aliases
            return switch (operator.toUpperCase()) {
                case "CONTAINS", "LIKE" -> QueryCondition.Operator.LIKE;
                case "EQUALS", "EQ" -> QueryCondition.Operator.EQ;
                case "NOT_EQUALS", "NEQ", "NE" -> QueryCondition.Operator.NE;
                case "GREATER_THAN", "GT" -> QueryCondition.Operator.GT;
                case "GREATER_EQUAL", "GTE", "GE" -> QueryCondition.Operator.GE;
                case "LESS_THAN", "LT" -> QueryCondition.Operator.LT;
                case "LESS_EQUAL", "LTE", "LE" -> QueryCondition.Operator.LE;
                default -> null;
            };
        }
    }

    // =========================================================================
    // Inner classes
    // =========================================================================

    private static class ModelSummary {
        String label;
        List<String> fields;
    }
}
