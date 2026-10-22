package com.auraboot.framework.intent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Structured result of analyzing a requirement document.
 * Contains entities, fields, relationships, state machines, and business rules.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentAnalysisResult {

    /**
     * Detected entities (models) from the requirement document.
     */
    private List<EntityDef> entities;

    /**
     * Detected relationships between entities.
     */
    private List<RelationshipDef> relationships;

    /**
     * Detected state machines for entities with lifecycle states.
     */
    private List<StateMachineDef> stateMachines;

    /**
     * Detected business rules / validation constraints.
     */
    private List<BusinessRuleDef> rules;

    /**
     * Summary of the analysis for display purposes.
     */
    private String summary;

    // ---- Inner classes ----

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityDef {
        private String code;
        private String name;
        private String description;
        private List<FieldDef> fields;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldDef {
        private String code;
        private String name;
        private String type; // STRING, INTEGER, DECIMAL, DATE, DATETIME, BOOLEAN, TEXT, REFERENCE, ENUM
        private boolean required;
        private String description;
        private String enumValues; // comma-separated for ENUM type
        private String referenceModel; // for REFERENCE type
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelationshipDef {
        private String fromEntity;
        private String toEntity;
        private String type; // ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY
        private String foreignKey;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StateMachineDef {
        private String entityCode;
        private String fieldCode;
        private List<String> states;
        private List<TransitionDef> transitions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransitionDef {
        private String from;
        private String to;
        private String action;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BusinessRuleDef {
        private String entityCode;
        private String ruleType; // VALIDATION, COMPUTATION, CONSTRAINT
        private String expression;
        private String description;
    }
}
