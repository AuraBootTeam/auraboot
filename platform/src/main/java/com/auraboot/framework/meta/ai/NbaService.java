package com.auraboot.framework.meta.ai;

import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * AI Next Best Action (NBA) service.
 *
 * Analyzes a record's current state and suggests the most impactful
 * next actions the user should take. Uses LLM to reason about:
 * - Current record status and field values
 * - Common business workflows for the model type
 * - Missing or incomplete data
 *
 * @author AuraBoot Team
 * @since 6.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NbaService {

    private final AiFieldProcessor aiFieldProcessor;
    private final DynamicDataService dynamicDataService;
    private final ObjectMapper objectMapper;
    private final SystemConfigService systemConfigService;
    private final MetaModelService metaModelService;

    /**
     * Generate next best action suggestions for a specific record.
     *
     * @param modelCode the model code (e.g., "crm_lead", "crm_opportunity")
     * @param recordPid the record PID
     * @return list of action suggestions, max 3
     */
    public List<NbaSuggestion> suggest(String modelCode, String recordPid) {
        // Check NBA toggle: model-level > global > default(false)
        if (!isNbaEnabled(modelCode)) {
            return Collections.emptyList();
        }

        // Fetch record data
        Map<String, Object> record;
        try {
            record = dynamicDataService.getById(modelCode, recordPid);
        } catch (Exception e) {
            log.warn("NBA: failed to fetch record {}/{}: {}", modelCode, recordPid, e.getMessage());
            return Collections.emptyList();
        }
        if (record == null || record.isEmpty()) {
            return Collections.emptyList();
        }

        // Build prompt
        String prompt = buildNbaPrompt(modelCode, record);

        AiFieldProcessor.AiGenerationRequest request = AiFieldProcessor.AiGenerationRequest.builder()
                .operation("generate")
                .prompt(prompt)
                .maxTokens(800)
                .temperature(0.4)
                .build();

        AiFieldProcessor.AiGenerationResult result = aiFieldProcessor.process(request);
        if (!result.isSuccess()) {
            log.debug("NBA: AI unavailable for {}/{}", modelCode, recordPid);
            return Collections.emptyList();
        }

        return parseSuggestions(result.getContent());
    }

    /**
     * Determine if NBA is enabled for the given model.
     * Priority: model extension.enableNba > global ai.nba.enabled > false
     */
    private boolean isNbaEnabled(String modelCode) {
        // 1. Check model-level override
        MetaModelDTO model = metaModelService.findByCode(modelCode);
        if (model != null && model.getExtension() != null) {
            Object modelFlag = model.getExtension().get("enableNba");
            if (modelFlag instanceof Boolean b) {
                return b;
            }
            if (modelFlag != null) {
                return Boolean.parseBoolean(modelFlag.toString());
            }
        }

        // 2. Fall back to global config (default: false)
        return systemConfigService.getBoolean(SystemConfigKeys.AI_NBA_ENABLED, false);
    }

    private String buildNbaPrompt(String modelCode, Map<String, Object> record) {
        // Build a concise record summary (exclude system fields)
        StringBuilder fields = new StringBuilder();
        for (Map.Entry<String, Object> entry : record.entrySet()) {
            String key = entry.getKey();
            if (key.equals("id") || key.equals("pid") || key.equals("tenant_id")
                    || key.equals("created_at") || key.equals("updated_at")
                    || key.equals("created_by") || key.equals("updated_by")
                    || key.equals("deleted_flag")) continue;
            Object val = entry.getValue();
            if (val != null && !val.toString().isBlank()) {
                fields.append("  ").append(key).append(": ").append(truncate(val.toString(), 100)).append("\n");
            }
        }

        return String.format("""
                You are a CRM/ERP business advisor. Based on the current record state, suggest 1-3 next best actions.

                Model: %s
                Record data:
                %s

                Rules:
                - Each suggestion should be actionable and specific
                - Consider the record's status/stage to determine what comes next
                - Flag any missing or incomplete fields that should be filled
                - Suggest follow-up activities (calls, emails, meetings) when appropriate
                - For CRM records, consider sales pipeline progression

                Respond ONLY with a JSON array (no markdown, no explanation):
                [
                  {
                    "title": "Short action title (under 40 chars)",
                    "description": "Why this action matters and what to do (1-2 sentences)",
                    "priority": "HIGH|MEDIUM|LOW",
                    "category": "FOLLOW_UP|DATA_QUALITY|STAGE_ADVANCE|RISK_ALERT|OPPORTUNITY"
                  }
                ]
                """, modelCode, fields);
    }

    private List<NbaSuggestion> parseSuggestions(String content) {
        try {
            String json = extractJsonArray(content);
            if (json == null) return Collections.emptyList();
            List<NbaSuggestion> suggestions = objectMapper.readValue(json,
                    new TypeReference<List<NbaSuggestion>>() {});
            // Cap at 3 suggestions
            if (suggestions.size() > 3) return suggestions.subList(0, 3);
            return suggestions;
        } catch (Exception e) {
            log.warn("NBA: failed to parse suggestions: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static String extractJsonArray(String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.startsWith("[")) return text;
        // Try markdown code block
        int codeStart = text.indexOf("```");
        if (codeStart >= 0) {
            int jsonStart = text.indexOf('[', codeStart);
            int jsonEnd = text.lastIndexOf(']');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                return text.substring(jsonStart, jsonEnd + 1);
            }
        }
        // Try raw array
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return null;
    }

    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NbaSuggestion {
        private String title;
        private String description;
        private String priority;  // HIGH, MEDIUM, LOW
        private String category;  // FOLLOW_UP, DATA_QUALITY, STAGE_ADVANCE, RISK_ALERT, OPPORTUNITY
    }
}
