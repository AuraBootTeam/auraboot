package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * DTO for importing model definitions from plugin manifest.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelDefinitionDTO {

    /**
     * Model code (unique within tenant).
     * Required.
     */
    private String code;

    /**
     * Display name (supports i18n with "displayName:zh-CN" format).
     */
    private String displayName;

    /**
     * Localized display names.
     */
    @JsonProperty("displayName:zh-CN")
    private String displayNameZhCN;

    @JsonProperty("displayName:en")
    private String displayNameEn;

    /**
     * Model description.
     */
    private String description;

    /**
     * Model type: ENTITY, AGGREGATE, VALUE_OBJECT, etc.
     */
    private String modelType;

    /**
     * Business object category: DOCUMENT, MASTER, TRANSACTION, ACTIVITY, REFERENCE, ENTITY.
     */
    private String modelCategory;

    /**
     * Table name for the model (optional, auto-generated if not specified).
     */
    private String tableName;

    /**
     * Whether this model is abstract (cannot be instantiated directly).
     */
    @Builder.Default
    private Boolean isAbstract = false;

    /**
     * Parent model code for inheritance.
     */
    private String parentModelCode;

    /**
     * Extension properties (stored in JSONB).
     */
    private Map<String, Object> extension;

    /**
     * Business meaning written for Agent / domain-aware tooling.
     * Persisted to ab_meta_model.semantic_description.
     */
    @JsonProperty("semantic_description")
    private String semanticDescription;

    /**
     * Domain category (e.g. CRM, FINANCE, INVENTORY, HR). Persisted to ab_meta_model.domain_category.
     */
    @JsonProperty("domain_category")
    private String domainCategory;

    /**
     * Data sensitivity (PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED).
     * Persisted to ab_meta_model.data_sensitivity (default 'internal' at DB level).
     */
    @JsonProperty("data_sensitivity")
    private String dataSensitivity;

    /**
     * Lifecycle description, e.g. "DRAFT → SUBMITTED → APPROVED".
     * Persisted to ab_meta_model.lifecycle_description.
     */
    @JsonProperty("lifecycle_description")
    private String lifecycleDescription;

    @JsonIgnore
    private Map<String, Object> unknownFields;

    @JsonAnySetter
    public void setUnknownField(String key, Object value) {
        if (unknownFields == null) {
            unknownFields = new HashMap<>();
        }
        unknownFields.put(key, value);
    }

    /**
     * Validate model definition has required fields.
     */
    public boolean isValid() {
        return code != null && !code.isBlank();
    }

    /**
     * Get effective display name (prefer localized).
     */
    public String getEffectiveDisplayName() {
        if (displayNameZhCN != null && !displayNameZhCN.isBlank()) {
            return displayNameZhCN;
        }
        if (displayNameEn != null && !displayNameEn.isBlank()) {
            return displayNameEn;
        }
        return displayName != null ? displayName : code;
    }
}
