package com.auraboot.framework.intent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.intent.dto.IntentAnalysisResult;
import com.auraboot.framework.intent.dto.IntentAnalysisResult.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Analyzes requirement documents (text/markdown) using LLM to extract
 * structured entities, fields, relationships, state machines, and business rules.
 *
 * Uses a hybrid approach: prompt template + LLM parsing.
 */
@Service
public class IntentAnalyzerService {

    private static final Logger log = LoggerFactory.getLogger(IntentAnalyzerService.class);
    private final ObjectMapper objectMapper;
    private final LlmClient llmClient;

    public IntentAnalyzerService(ObjectMapper objectMapper, LlmClient llmClient) {
        this.objectMapper = objectMapper;
        this.llmClient = llmClient;
    }

    /**
     * Analyze a requirement document and return structured analysis.
     *
     * @param content the document content
     * @param format  content format ("text" or "markdown")
     * @return structured analysis result
     */
    public IntentAnalysisResult analyze(String content, String format) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Requirement content must not be empty");
        }

        String prompt = buildAnalysisPrompt(content, format);
        String llmResponse = llmClient.chat(prompt);

        return parseAnalysisResponse(llmResponse);
    }

    // ---- Prompt construction ----

    private String buildAnalysisPrompt(String content, String format) {
        return """
            You are a business analyst AI. Analyze the following requirement document and extract:
            1. **Entities**: business objects/models with their fields
            2. **Relationships**: how entities relate to each other
            3. **State Machines**: lifecycle states and transitions for entities
            4. **Business Rules**: validation rules, computations, constraints

            For each entity, extract fields with:
            - code (snake_case, lowercase)
            - name (human-readable)
            - type: one of STRING, INTEGER, DECIMAL, DATE, DATETIME, BOOLEAN, TEXT, REFERENCE, ENUM
            - required: true/false
            - description
            - enumValues (comma-separated, for ENUM type)
            - referenceModel (for REFERENCE type)

            Entity codes should be snake_case (e.g. "order", "order_item").
            Field codes should be snake_case with entity prefix (e.g. "ord_name", "ord_status").

            Respond ONLY with a JSON object matching this structure:
            {
              "entities": [{ "code": "", "name": "", "description": "", "fields": [...] }],
              "relationships": [{ "fromEntity": "", "toEntity": "", "type": "ONE_TO_MANY|MANY_TO_ONE|MANY_TO_MANY", "foreignKey": "", "description": "" }],
              "stateMachines": [{ "entityCode": "", "fieldCode": "", "states": [...], "transitions": [{ "from": "", "to": "", "action": "", "description": "" }] }],
              "rules": [{ "entityCode": "", "ruleType": "VALIDATION|COMPUTATION|CONSTRAINT", "expression": "", "description": "" }],
              "summary": "Brief summary of the analysis"
            }

            --- DOCUMENT (format: %s) ---
            %s
            """.formatted(format, content);
    }

    // ---- Response parsing ----

    IntentAnalysisResult parseAnalysisResponse(String llmResponse) {
        try {
            // Extract JSON from the LLM response (may contain markdown code blocks)
            String json = extractJson(llmResponse);
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() {});

            IntentAnalysisResult result = new IntentAnalysisResult();
            result.setSummary(getString(raw, "summary"));
            result.setEntities(parseEntities(raw));
            result.setRelationships(parseRelationships(raw));
            result.setStateMachines(parseStateMachines(raw));
            result.setRules(parseRules(raw));

            return result;
        } catch (Exception e) {
            log.error("Failed to parse LLM analysis response: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse intent analysis result from LLM response", e);
        }
    }

    private String extractJson(String text) {
        // Strip markdown code fences if present
        String trimmed = text.strip();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.strip();
    }

    @SuppressWarnings("unchecked")
    private List<EntityDef> parseEntities(Map<String, Object> raw) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) raw.getOrDefault("entities", List.of());
        List<EntityDef> result = new ArrayList<>();
        for (Map<String, Object> item : list) {
            List<FieldDef> fields = new ArrayList<>();
            List<Map<String, Object>> rawFields = (List<Map<String, Object>>) item.getOrDefault("fields", List.of());
            for (Map<String, Object> f : rawFields) {
                fields.add(FieldDef.builder()
                        .code(getString(f, "code"))
                        .name(getString(f, "name"))
                        .type(getString(f, "type"))
                        .required(Boolean.TRUE.equals(f.get("required")))
                        .description(getString(f, "description"))
                        .enumValues(getString(f, "enumValues"))
                        .referenceModel(getString(f, "referenceModel"))
                        .build());
            }
            result.add(EntityDef.builder()
                    .code(getString(item, "code"))
                    .name(getString(item, "name"))
                    .description(getString(item, "description"))
                    .fields(fields)
                    .build());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<RelationshipDef> parseRelationships(Map<String, Object> raw) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) raw.getOrDefault("relationships", List.of());
        List<RelationshipDef> result = new ArrayList<>();
        for (Map<String, Object> item : list) {
            result.add(RelationshipDef.builder()
                    .fromEntity(getString(item, "fromEntity"))
                    .toEntity(getString(item, "toEntity"))
                    .type(getString(item, "type"))
                    .foreignKey(getString(item, "foreignKey"))
                    .description(getString(item, "description"))
                    .build());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<StateMachineDef> parseStateMachines(Map<String, Object> raw) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) raw.getOrDefault("stateMachines", List.of());
        List<StateMachineDef> result = new ArrayList<>();
        for (Map<String, Object> item : list) {
            List<TransitionDef> transitions = new ArrayList<>();
            List<Map<String, Object>> rawTr = (List<Map<String, Object>>) item.getOrDefault("transitions", List.of());
            for (Map<String, Object> t : rawTr) {
                transitions.add(TransitionDef.builder()
                        .from(getString(t, "from"))
                        .to(getString(t, "to"))
                        .action(getString(t, "action"))
                        .description(getString(t, "description"))
                        .build());
            }
            List<String> states = (List<String>) item.getOrDefault("states", List.of());
            result.add(StateMachineDef.builder()
                    .entityCode(getString(item, "entityCode"))
                    .fieldCode(getString(item, "fieldCode"))
                    .states(states)
                    .transitions(transitions)
                    .build());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<BusinessRuleDef> parseRules(Map<String, Object> raw) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) raw.getOrDefault("rules", List.of());
        List<BusinessRuleDef> result = new ArrayList<>();
        for (Map<String, Object> item : list) {
            result.add(BusinessRuleDef.builder()
                    .entityCode(getString(item, "entityCode"))
                    .ruleType(getString(item, "ruleType"))
                    .expression(getString(item, "expression"))
                    .description(getString(item, "description"))
                    .build());
        }
        return result;
    }

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
