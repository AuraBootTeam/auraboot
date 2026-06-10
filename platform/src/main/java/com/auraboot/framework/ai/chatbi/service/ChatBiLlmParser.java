package com.auraboot.framework.ai.chatbi.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LLM-backed question parser for the model-direct ChatBI path.
 *
 * <p>Sends the natural-language question plus a model/field catalog to the
 * configured LLM provider and parses the structured JSON reply into a
 * {@link ParsedQuery}. Returns {@code null} on any failure (no provider
 * configured, provider error, malformed reply) so the caller can fall back
 * to keyword parsing — the LLM path is an accuracy upgrade, never a
 * hard dependency.</p>
 *
 * <p>Distinct from {@code chatbi/v2}: v2 is the conversation-scoped semantic-layer
 * pipeline (semantic models + token compiler); this parser serves the stateless
 * model-direct endpoint {@code POST /api/ai/chat-bi/query}.</p>
 *
 * @author AuraBoot Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatBiLlmParser {

    static final Set<String> ALLOWED_AGGREGATIONS = Set.of("count", "sum", "avg");
    static final Set<String> ALLOWED_OPERATORS = Set.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "LIKE");

    private static final int MAX_FIELDS_PER_MODEL = 30;
    private static final int MAX_CATALOG_MODELS = 50;
    private static final int MAX_LIMIT = 5000;

    private final LlmProviderFactory llmProviderFactory;
    private final ObjectMapper objectMapper;

    /**
     * Structured query parsed from the LLM reply. Field codes are validated by
     * the caller against the resolved model definition before use.
     */
    @Data
    @Builder
    public static class ParsedQuery {
        private String modelCode;
        private String aggregationFunction; // count | sum | avg | null
        private String aggregationField;
        private String groupByField;
        private String sortOrder;           // asc | desc
        private Integer limit;
        private boolean trend;
        private List<ParsedFilter> filters;
        private String interpretation;
    }

    /** A single validated filter condition from the LLM reply. */
    @Data
    @Builder
    public static class ParsedFilter {
        private String fieldCode;
        private String operator; // EQ/NE/GT/GTE/LT/LTE/LIKE
        private Object value;
    }

    /**
     * Try to parse the question via the configured LLM provider.
     *
     * @param tenantId       current tenant
     * @param question       the raw natural-language question
     * @param explicitModel  model resolved from the request's modelCode, or null
     *                       when the LLM should choose from the catalog
     * @param catalogModels  candidate models (with fields) to present when no
     *                       explicit model was given; ignored when explicitModel set
     * @return parsed query, or {@code null} when the LLM path is unavailable or failed
     */
    public ParsedQuery tryParse(Long tenantId, String question, ModelDefinition explicitModel,
                                List<ModelDefinition> catalogModels) {
        LlmProviderFactory.ProviderConfig config = resolveFirstAvailableConfig(tenantId);
        if (config == null) {
            return null;
        }
        try {
            String systemPrompt = buildSystemPrompt(explicitModel, catalogModels);
            String providerCode = LlmProviderFactory.effectiveProviderCode(null, config);
            LlmProvider provider = llmProviderFactory.getProvider(providerCode);
            LlmChatRequest chatRequest = LlmChatRequest.builder()
                    .model(config.getDefaultModel())
                    .systemPrompt(systemPrompt)
                    .messages(List.of(LlmChatRequest.Message.builder()
                            .role("user")
                            .content(question)
                            .build()))
                    .maxTokens(1024)
                    .build();

            LlmChatResponse response = provider.chat(chatRequest, config.getApiKey(), config.getBaseUrl());
            String text = extractTextContent(response);
            if (text == null || text.isBlank()) {
                log.warn("ChatBI LLM parse returned empty response");
                return null;
            }
            return toParsedQuery(stripMarkdownFences(text));
        } catch (Exception e) {
            log.warn("ChatBI LLM parse failed, caller will fall back to keyword parsing: {}", e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Prompt construction
    // =========================================================================

    private String buildSystemPrompt(ModelDefinition explicitModel, List<ModelDefinition> catalogModels) {
        StringBuilder sb = new StringBuilder();
        sb.append("You translate a business-intelligence question into a JSON query plan.\n");
        sb.append("Reply with ONLY a JSON object, no prose, using exactly this shape:\n");
        sb.append("{\"modelCode\": string, \"aggregation\": \"count\"|\"sum\"|\"avg\"|null, ");
        sb.append("\"aggregationField\": string|null, \"groupByField\": string|null, ");
        sb.append("\"sortOrder\": \"asc\"|\"desc\", \"limit\": number, \"trend\": boolean, ");
        sb.append("\"filters\": [{\"fieldCode\": string, \"operator\": \"EQ\"|\"NE\"|\"GT\"|\"GTE\"|\"LT\"|\"LTE\"|\"LIKE\", \"value\": any}], ");
        sb.append("\"interpretation\": string}\n");
        sb.append("Rules: only use fieldCode values listed below; omit filters you cannot ground; ");
        sb.append("limit defaults to 50, max 5000; use \"trend\": true for time-series questions.\n\n");

        if (explicitModel != null) {
            sb.append("Target model (already chosen by the caller — echo its code):\n");
            appendModel(sb, explicitModel);
        } else {
            sb.append("Available models — choose the single best modelCode:\n");
            int count = 0;
            for (ModelDefinition m : catalogModels) {
                if (count++ >= MAX_CATALOG_MODELS) break;
                appendModel(sb, m);
            }
        }
        return sb.toString();
    }

    private void appendModel(StringBuilder sb, ModelDefinition model) {
        sb.append("- ").append(model.getCode());
        if (model.getDisplayName() != null) {
            sb.append(" (").append(model.getDisplayName()).append(")");
        }
        sb.append(": fields [");
        List<String> fieldDescs = new ArrayList<>();
        if (model.getFields() != null) {
            int count = 0;
            for (FieldDefinition f : model.getFields()) {
                if (count++ >= MAX_FIELDS_PER_MODEL) break;
                String desc = f.getCode();
                if (f.getDataType() != null) {
                    desc += ":" + f.getDataType();
                }
                fieldDescs.add(desc);
            }
        }
        sb.append(String.join(", ", fieldDescs)).append("]\n");
    }

    // =========================================================================
    // Reply parsing
    // =========================================================================

    private ParsedQuery toParsedQuery(String json) throws Exception {
        Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

        String modelCode = asString(parsed.get("modelCode"));
        if (modelCode == null || modelCode.isBlank()) {
            log.warn("ChatBI LLM reply did not identify a modelCode");
            return null;
        }

        String aggregation = normalizeLower(asString(parsed.get("aggregation")));
        if (aggregation != null && !ALLOWED_AGGREGATIONS.contains(aggregation)) {
            log.warn("ChatBI LLM reply used unsupported aggregation '{}'; dropping aggregation", aggregation);
            aggregation = null;
        }

        Integer limit = null;
        if (parsed.get("limit") instanceof Number n) {
            limit = Math.max(1, Math.min(n.intValue(), MAX_LIMIT));
        }

        String sortOrder = normalizeLower(asString(parsed.get("sortOrder")));
        if (!"asc".equals(sortOrder) && !"desc".equals(sortOrder)) {
            sortOrder = "desc";
        }

        List<ParsedFilter> filters = new ArrayList<>();
        if (parsed.get("filters") instanceof List<?> rawFilters) {
            for (Object item : rawFilters) {
                if (!(item instanceof Map<?, ?> fm)) continue;
                String fieldCode = asString(fm.get("fieldCode"));
                String operator = asString(fm.get("operator"));
                Object value = fm.get("value");
                if (fieldCode == null || operator == null || value == null) continue;
                operator = operator.toUpperCase(Locale.ROOT);
                if (!ALLOWED_OPERATORS.contains(operator)) {
                    log.warn("ChatBI LLM reply used unsupported filter operator '{}'; dropping filter on {}",
                            operator, fieldCode);
                    continue;
                }
                filters.add(ParsedFilter.builder().fieldCode(fieldCode).operator(operator).value(value).build());
            }
        }

        return ParsedQuery.builder()
                .modelCode(modelCode)
                .aggregationFunction(aggregation)
                .aggregationField(asString(parsed.get("aggregationField")))
                .groupByField(asString(parsed.get("groupByField")))
                .sortOrder(sortOrder)
                .limit(limit)
                .trend(Boolean.TRUE.equals(parsed.get("trend")))
                .filters(filters)
                .interpretation(asString(parsed.get("interpretation")))
                .build();
    }

    private String asString(Object o) {
        return o instanceof String s ? s : null;
    }

    private String normalizeLower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    // =========================================================================
    // LLM helpers (same pattern as AiSearchServiceImpl / AiTranslationService)
    // =========================================================================

    private LlmProviderFactory.ProviderConfig resolveFirstAvailableConfig(Long tenantId) {
        try {
            LlmProviderFactory.ProviderConfig config = llmProviderFactory.resolveConfig(tenantId, "anthropic");
            if (config != null) return config;
            for (LlmProviderFactory.ProviderInfo info : llmProviderFactory.listConfiguredProviders(tenantId)) {
                LlmProviderFactory.ProviderConfig c = llmProviderFactory.resolveConfig(tenantId, info.getProviderCode());
                if (c != null) return c;
            }
        } catch (Exception e) {
            log.debug("ChatBI LLM availability check failed: {}", e.getMessage());
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
}
