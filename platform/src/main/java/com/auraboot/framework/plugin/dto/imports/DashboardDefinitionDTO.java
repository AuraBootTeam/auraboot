package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DTO for importing dashboard definitions from {@code config/dashboards/*.json}.
 *
 * <p>This is the <em>first-class</em> dashboard import contract (Plan #8).
 * Files in {@code config/dashboards/} express the Dashboard DSL directly —
 * no conversion step is needed (contrast with the legacy {@code kind=dashboard}
 * page path that goes through {@link com.auraboot.framework.dashboard.migration.BlockToDashboardConverter}).
 *
 * <p>Required fields: {@code code}, {@code title}, {@code widgets}.
 *
 * <p>Example JSON:
 * <pre>
 * {
 *   "code": "crm_overview",
 *   "title": "CRM Overview",
 *   "description": "Key CRM metrics",
 *   "scope": "global",
 *   "status": "published",
 *   "isDefault": true,
 *   "layoutConfig": { "columns": 12, "rowHeight": 100, "gap": 16, "compactType": "vertical" },
 *   "widgets": [
 *     {
 *       "id": "w1", "type": "smart-bar-chart",
 *       "x": 0, "y": 0, "w": 6, "h": 3,
 *       "title": "Revenue by Month",
 *       "config": { "title": "Revenue by Month", "dataSource": { ... } }
 *     }
 *   ]
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardDefinitionDTO {

    /**
     * Unique code for this dashboard within the tenant.
     * Required.
     */
    private String code;

    /**
     * Dashboard title (display name). Can be a plain string.
     * Required.
     */
    private String title;

    /**
     * Optional human-readable description.
     */
    private String description;

    /**
     * Visibility scope. Defaults to {@code "global"} for plugin-declared dashboards.
     * Allowed values: {@code global}, {@code personal}, {@code team}.
     */
    @Builder.Default
    private String scope = "global";

    /**
     * Lifecycle status. Defaults to {@code "published"} for plugin-declared dashboards.
     * Allowed values: {@code draft}, {@code published}.
     */
    @Builder.Default
    private String status = "published";

    /**
     * Whether this dashboard should become the default dashboard after import.
     */
    @Builder.Default
    private Boolean isDefault = false;

    /**
     * Layout configuration object.
     * Shape: {@code { columns, rowHeight, gap, compactType }}.
     * Defaults are applied at import time when missing.
     */
    private Map<String, Object> layoutConfig;

    /**
     * Widget array. Required and must be non-empty.
     * Each widget shape: {@code { id, type, x, y, w, h, title, config: { ... } }}.
     */
    private List<Object> widgets;

    /**
     * Sort order hint for display ordering.
     */
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * Captures unknown JSON fields for forward-compatibility warning.
     */
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
     * Validate that all required fields are present and non-blank.
     *
     * @return {@code true} if the DTO is valid for import
     */
    @JsonIgnore
    public boolean isValid() {
        return code != null && !code.isBlank()
                && title != null && !title.isBlank()
                && widgets != null && !widgets.isEmpty();
    }

    /**
     * Return effective scope, defaulting to {@code "global"} when null.
     */
    @JsonIgnore
    public String getEffectiveScope() {
        return scope != null && !scope.isBlank() ? scope : "global";
    }

    /**
     * Return effective status, defaulting to {@code "published"} when null.
     */
    @JsonIgnore
    public String getEffectiveStatus() {
        return status != null && !status.isBlank() ? status : "published";
    }
}
