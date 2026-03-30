package com.auraboot.framework.dsl.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Top-level response for the DSL introspection endpoint.
 * Provides a complete snapshot of all DSL metadata (models, fields, commands, pages)
 * and platform capabilities for third-party tooling and agents.
 */
@Data
@Builder
public class DslIntrospectionResponse {

    /**
     * Schema protocol version.
     */
    private String version;

    /**
     * ISO-8601 export timestamp.
     */
    private String exportedAt;

    /**
     * Tenant ID for this snapshot.
     */
    private Long tenantId;

    /**
     * Summary statistics.
     */
    private IntrospectionStats stats;

    /**
     * All models with their fields, commands, and pages.
     * Only present when scope includes "models".
     */
    private List<ModelIntrospection> models;

    /**
     * Platform capability catalog.
     * Only present when scope includes "capabilities".
     */
    private CapabilityCatalog capabilities;

    @Data
    @Builder
    public static class IntrospectionStats {
        private int modelCount;
        private int fieldCount;
        private int commandCount;
        private int pageCount;
    }

    @Data
    @Builder
    public static class ModelIntrospection {
        private String code;
        private String displayName;
        private String description;
        private String modelCategory;
        private String modelType;
        private String tableName;
        private String status;
        private Integer version;
        private List<FieldIntrospection> fields;
        private List<CommandIntrospection> commands;
        private List<PageIntrospection> pages;
    }

    @Data
    @Builder
    public static class FieldIntrospection {
        private String code;
        private String dataType;
        private Boolean required;
        private Boolean searchable;
        private Integer sortOrder;
        private Map<String, Object> feature;
        private Map<String, Object> refTarget;
        private Map<String, Object> uiSchema;
    }

    @Data
    @Builder
    public static class CommandIntrospection {
        private String code;
        private String displayName;
        private String description;
        private String modelCode;
        private String cmdRiskLevel;
        private String agentHint;
        private String status;
        private Object inputSchema;
        private Object executionConfig;
    }

    @Data
    @Builder
    public static class PageIntrospection {
        private String pageKey;
        private String name;
        private String kind;
        private String profile;
        private String modelCode;
        private String status;
        private Integer schemaVersion;
    }

    @Data
    @Builder
    public static class CapabilityCatalog {
        private List<String> dataTypes;
        private List<String> blockTypes;
        private List<String> commandTypes;
        private List<String> renderComponents;
        private List<String> expressionFunctions;
        private List<String> sideEffectHandlers;
        private List<String> automationActions;
    }
}
