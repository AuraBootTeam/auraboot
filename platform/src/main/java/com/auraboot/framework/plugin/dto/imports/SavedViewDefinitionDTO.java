package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * DTO for importing saved view definitions from plugin manifest.
 * Saved views define pre-configured view types (TABLE, KANBAN, CALENDAR, etc.)
 * that are available to all users as GLOBAL views after plugin import.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SavedViewDefinitionDTO {

    /**
     * View name displayed to user.
     * Required.
     */
    private String name;

    /**
     * View description.
     */
    private String description;

    /**
     * Associated model code.
     * Required.
     */
    private String modelCode;

    /**
     * Associated page key (optional).
     */
    private String pageKey;

    /**
     * View scope: PERSONAL, TEAM, GLOBAL.
     * Defaults to GLOBAL for plugin-imported views.
     */
    @Builder.Default
    private String scope = "global";

    /**
     * View type: TABLE, KANBAN, CALENDAR, GALLERY, GANTT, TREE.
     * Required.
     */
    private String viewType;

    /**
     * Stable plugin-owned key for upgrade-safe SavedView matching.
     * When present, plugin imports update the same preset by viewKey even if
     * its display name changes between plugin versions.
     */
    private String viewKey;

    /**
     * Ownership marker for imported presets. Defaults to "plugin" during import.
     */
    private String managedBy;

    /**
     * Whether the imported preset is read-only in normal runtime editing.
     * Defaults to true during plugin import.
     */
    private Boolean locked;

    /**
     * Whether users may copy this preset into a personal editable view.
     * Defaults to true during plugin import.
     */
    private Boolean allowUserCopy;

    /**
     * Whether tenant users may override this preset directly.
     * Defaults to true for manifest metadata compatibility; runtime behavior
     * may still require elevated permissions.
     */
    private Boolean allowUserOverride;

    /**
     * View configuration as a flexible map (serialized to JSONB).
     * For KANBAN: groupByField, titleField, descriptionField, idField, cardFields, kanbanAggregations, draggable, showCount, showAggregations
     * For CALENDAR: calendarDateField, calendarTitleField, calendarEndDateField, calendarColorField, calendarDefaultView
     * For TABLE: columns, sorts, filters, pagination
     */
    private Map<String, Object> viewConfig;

    /**
     * Whether this is the default view for the model/page.
     */
    private Boolean isDefault;

    /**
     * Sort order for view list display.
     */
    private Integer sortOrder;

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
     * Validate saved view has required fields.
     */
    public boolean isValid() {
        return name != null && !name.isBlank()
                && modelCode != null && !modelCode.isBlank()
                && viewType != null && !viewType.isBlank();
    }

    /**
     * Get a unique key for deduplication. Prefer the stable plugin viewKey when present,
     * otherwise fall back to the legacy modelCode + pageKey + name + viewType key.
     */
    @JsonIgnore
    public String getUniqueKey() {
        if (viewKey != null && !viewKey.isBlank()) {
            return modelCode + "::" + (pageKey != null ? pageKey : "") + "::" + viewKey;
        }
        return modelCode + "::" + (pageKey != null ? pageKey : "") + "::" + name + "::" + viewType;
    }
}
