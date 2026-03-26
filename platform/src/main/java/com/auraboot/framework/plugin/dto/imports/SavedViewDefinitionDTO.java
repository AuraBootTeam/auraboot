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
     * Get a unique key for deduplication (modelCode + pageKey + name + viewType).
     */
    @JsonIgnore
    public String getUniqueKey() {
        return modelCode + "::" + (pageKey != null ? pageKey : "") + "::" + name + "::" + viewType;
    }
}
