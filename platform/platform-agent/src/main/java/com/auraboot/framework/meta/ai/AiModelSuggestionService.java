package com.auraboot.framework.meta.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service layer for AI-powered model structure suggestion.
 *
 * Extracts core logic from AiModelSuggestionController so it can be
 * reused by both the REST endpoint and AuraBot tool execution.
 *
 * @author AuraBoot Team
 * @since 3.1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelSuggestionService {

    private final AiFieldProcessor aiFieldProcessor;
    private final ObjectMapper objectMapper;

    /**
     * Suggest a model structure from a natural language description.
     *
     * @param description business scenario description
     * @param language    language for field names: "en" or "zh" (default "en")
     * @return model suggestion with fields and views
     */
    public ModelSuggestion suggestModel(String description, String language) {
        AiFieldProcessor.AiGenerationRequest genRequest = AiFieldProcessor.AiGenerationRequest.builder()
                .operation("generate")
                .prompt(buildModelSuggestionPrompt(description, language))
                .maxTokens(2000)
                .temperature(0.3)
                .build();

        AiFieldProcessor.AiGenerationResult result = aiFieldProcessor.process(genRequest);

        if (result.isSuccess()) {
            ModelSuggestion suggestion = parseModelSuggestion(result.getContent(), description);
            if (suggestion != null) {
                return suggestion;
            }
            log.warn("AI returned content but parsing failed for description: {}", description);
        }

        // AI unavailable or parsing failed — return null, caller handles error
        log.warn("AI model suggestion unavailable for: {}", description);
        return null;
    }

    private String buildModelSuggestionPrompt(String description, String language) {
        String lang = language != null ? language : "en";
        return String.format("""
                Based on the following business scenario description, suggest a data model structure.
                Return a JSON object with the following format:
                {
                  "modelCode": "snake_case_name",
                  "modelName": "Display Name",
                  "description": "Brief description",
                  "fields": [
                    {
                      "fieldCode": "snake_case",
                      "fieldName": "Display Name",
                      "dataType": "STRING|INTEGER|DECIMAL|DATE|DATETIME|BOOLEAN|TEXT|ENUM",
                      "required": true/false,
                      "description": "Field description"
                    }
                  ],
                  "suggestedViews": ["table", "kanban", "calendar"]
                }

                Use %s for field names and descriptions.

                Business scenario: %s
                """, lang.equals("zh") ? "Chinese" : "English", description);
    }

    private ModelSuggestion parseModelSuggestion(String content, String description) {
        try {
            int jsonStart = content.indexOf('{');
            int jsonEnd = content.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String json = content.substring(jsonStart, jsonEnd + 1);
                return objectMapper.readValue(json, ModelSuggestion.class);
            }
        } catch (Exception e) {
            log.warn("Failed to parse AI model suggestion response: {}", e.getMessage());
        }
        return null;
    }

    @Data
    public static class ModelSuggestion {
        private String modelCode;
        private String modelName;
        private String description;
        private List<FieldSuggestion> fields;
        private List<String> suggestedViews;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldSuggestion {
        private String fieldCode;
        private String fieldName;
        private String dataType;
        private boolean required;
        private String description;
    }
}
