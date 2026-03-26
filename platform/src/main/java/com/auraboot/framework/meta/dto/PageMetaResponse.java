package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * GAP-004: Rich metadata response for a dynamic page/model.
 *
 * Returned by {@code GET /api/dynamic/{pageKey}/meta} and consumed by mobile apps
 * (iOS/Android), external integrations, and the AI modeling assistant.
 */
@Data
@Builder
public class PageMetaResponse {

    /** Page key as received in the path variable. */
    private String pageKey;

    /** Underlying model code (converted from pageKey). */
    private String modelCode;

    /** Human-readable model title (displayName or code). */
    private String title;

    /** Field descriptors with type information and enum options for DICT fields. */
    private List<FieldMeta> fields;

    /** Full page DSL schema JSON (may be null if no PageSchema exists). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> schema;

    /** Current user's permissions on this resource. */
    private Permissions permissions;

    /** View types available for this page based on the DSL schema configuration. */
    private List<String> availableViews;

    // ==================== Nested DTOs ====================

    @Data
    @Builder
    public static class FieldMeta {
        /** Field code (matches model field code). */
        private String code;
        /** I18n-resolved display name. */
        private String displayName;
        /** Data type: TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, DICT, REFERENCE, FILE, etc. */
        private String fieldType;
        /** Whether this field must be filled in on create/update. */
        private boolean required;
        /** Enum/dict options for DICT fields. Null for non-dict fields. */
        private List<OptionItem> options;
    }

    @Data
    @Builder
    public static class OptionItem {
        private String value;
        private String label;
    }

    @Data
    @Builder
    public static class Permissions {
        private boolean canCreate;
        private boolean canUpdate;
        private boolean canDelete;
        private boolean canExport;
    }
}
