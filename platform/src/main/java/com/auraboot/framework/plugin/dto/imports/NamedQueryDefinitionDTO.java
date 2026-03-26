package com.auraboot.framework.plugin.dto.imports;

import com.auraboot.framework.meta.dto.NamedQueryFieldRequest;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DTO for importing named query definitions from plugin manifest.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NamedQueryDefinitionDTO {

    /**
     * Query code (unique within tenant).
     * Required.
     */
    private String code;

    /**
     * Query title.
     */
    private String title;

    /**
     * Localized titles.
     */
    @JsonProperty("title:zh-CN")
    private String titleZhCN;

    @JsonProperty("title:en")
    private String titleEn;

    /**
     * Query description.
     */
    private String description;

    /**
     * FROM SQL clause.
     * Required.
     */
    private String fromSql;

    /**
     * Base where condition JSON.
     */
    private JsonNode baseWhere;

    /**
     * Default order JSON.
     */
    private JsonNode defaultOrder;

    /**
     * Lifecycle status: DRAFT, TESTING, PUBLISHED, DEPRECATED, ARCHIVED.
     */
    @Builder.Default
    private String status = "draft";

    /**
     * Query fields.
     * Accepts both "fields" (API format) and "outputFields" (plugin JSON format).
     */
    @JsonAlias("outputFields")
    private List<NamedQueryFieldRequest> fields;

    /**
     * Tags.
     */
    private List<String> tags;

    /**
     * Metadata JSON.
     */
    private JsonNode metadata;

    /**
     * Whether to validate SQL when importing.
     */
    @Builder.Default
    private Boolean validateSql = true;

    /**
     * Whether to check permissions when importing.
     */
    @Builder.Default
    private Boolean checkPermissions = true;

    /**
     * Extension properties.
     */
    private Map<String, Object> extension;

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
     * Validate named query has required fields.
     * Note: columnExpr and dataType are NOT validated here because plugin JSON
     * format uses simplified outputFields (only "code"). These are auto-filled
     * by PluginResourceImporterImpl.cloneNamedQueryFields() during import.
     */
    public boolean isValid() {
        if (code == null || code.isBlank()) return false;
        if (fromSql == null || fromSql.isBlank()) return false;
        if (fields != null) {
            for (NamedQueryFieldRequest field : fields) {
                if (field.getFieldCode() == null || field.getFieldCode().isBlank()) return false;
            }
        }
        return true;
    }

    /**
     * Get effective title.
     */
    public String getEffectiveTitle() {
        if (titleZhCN != null && !titleZhCN.isBlank()) {
            return titleZhCN;
        }
        if (titleEn != null && !titleEn.isBlank()) {
            return titleEn;
        }
        return title != null ? title : code;
    }
}
