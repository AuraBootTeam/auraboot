package com.auraboot.framework.view.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ViewConfig - JSONB configuration for SavedView
 * Contains all view customization options
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ViewConfig {

    /**
     * Column configurations
     */
    private List<ColumnConfig> columns;

    /**
     * Sort configurations
     */
    private List<SortConfig> sorts;

    /**
     * Filter configurations
     */
    private List<FilterConfig> filters;

    /**
     * Group by configurations
     */
    private List<GroupByConfig> groupBy;

    /**
     * Pagination configuration
     */
    private PaginationConfig pagination;

    /**
     * Whether to show row numbers
     */
    private Boolean showRowNumbers;

    /**
     * Table density: compact, default, comfortable (legacy)
     */
    private String density;

    /**
     * Row height preset: short (32px), medium (44px), tall (60px), extra-tall (80px)
     */
    private String rowHeight;

    /**
     * Conditional formatting rules for TABLE view.
     * Each rule: fieldCode + operator + value → style (backgroundColor, textColor, bold).
     */
    private List<java.util.Map<String, Object>> conditionalFormats;

    /**
     * Toolbar action button configurations for TABLE view.
     */
    private List<ToolbarActionConfig> toolbarActions;

    // ==================== Kanban Fields ====================

    /**
     * Field used to group cards into columns (for KANBAN view)
     */
    private String groupByField;

    /**
     * Field used as card ID (for KANBAN view), defaults to 'id'
     */
    private String idField;

    /**
     * Field used as card title (for KANBAN view)
     */
    private String titleField;

    /**
     * Field used as card description (for KANBAN view)
     */
    private String descriptionField;

    /**
     * Fields to display on kanban cards
     */
    private List<KanbanCardFieldConfig> cardFields;

    /**
     * Aggregation configurations for kanban columns
     */
    private List<KanbanAggregationConfig> kanbanAggregations;

    /**
     * Whether kanban cards can be dragged between columns
     */
    private Boolean draggable;

    /**
     * Whether to show card count in kanban column headers
     */
    private Boolean showCount;

    /**
     * Whether to show aggregation values in kanban column headers
     */
    private Boolean showAggregations;

    /**
     * Optional dict code that backs the groupByField. When present, the
     * kanban renders the full set of dict enum values as columns (in dict
     * sortOrder), even for stages with zero cards — matching CRM-style
     * pipeline visibility (Salesforce/HubSpot). Without this, only stages
     * present in the data render. See backlog 2026-05-08 Gap 2.
     */
    private String groupByDictCode;

    /**
     * Optional terminal-stage hint for kanban views. Maps `won` / `lost`
     * arrays of dict values that should receive terminal visual treatment
     * regardless of dict-extension metadata. Stored as a JSON object so
     * plugin views can express both buckets independently of the dict.
     */
    private java.util.Map<String, java.util.List<String>> terminalStages;

    // ==================== Calendar Fields ====================

    /**
     * Date field for calendar event start (for CALENDAR view)
     */
    private String calendarDateField;

    /**
     * Title field for calendar event display (for CALENDAR view)
     */
    private String calendarTitleField;

    /**
     * End date field for multi-day events (for CALENDAR view)
     */
    private String calendarEndDateField;

    /**
     * Color-by field for event color coding (for CALENDAR view)
     */
    private String calendarColorField;

    /**
     * Default calendar view: dayGridMonth, timeGridWeek, listWeek
     */
    private String calendarDefaultView;

    // ==================== Gallery Fields ====================

    /**
     * Field containing image URL (for GALLERY view)
     */
    private String galleryImageField;

    /**
     * Field used as card title (for GALLERY view)
     */
    private String galleryTitleField;

    /**
     * Field used as card description (for GALLERY view)
     */
    private String galleryDescriptionField;

    /**
     * Number of grid columns: 2, 3, 4, 6 (for GALLERY view)
     */
    private Integer galleryColumns;

    /**
     * Image aspect ratio: square, 4:3, 16:9, auto (for GALLERY view)
     */
    private String galleryAspectRatio;

    /**
     * Whether to show title overlay on cards (for GALLERY view)
     */
    private Boolean galleryShowTitle;

    /**
     * Whether to show description on cards (for GALLERY view)
     */
    private Boolean galleryShowDescription;

    /**
     * Additional fields to display on gallery cards (for GALLERY view)
     */
    private List<String> galleryDisplayFields;

    // ==================== Gantt Fields ====================

    /**
     * Start date field (for GANTT view)
     */
    private String ganttStartDateField;

    /**
     * End date field (for GANTT view)
     */
    private String ganttEndDateField;

    /**
     * Title field for task bars (for GANTT view)
     */
    private String ganttTitleField;

    /**
     * Progress field 0-100 (for GANTT view)
     */
    private String ganttProgressField;

    /**
     * Dependency field - comma-separated IDs (for GANTT view)
     */
    private String ganttDependencyField;

    /**
     * Default view mode: Day, Week, Month (for GANTT view)
     */
    private String ganttDefaultView;

    // ==================== Timeline Fields ====================

    /**
     * Start date/time field for timeline bars (for TIMELINE view)
     */
    private String timelineStartField;

    /**
     * End date/time field for timeline bars (for TIMELINE view)
     */
    private String timelineEndField;

    /**
     * Title field for timeline bars (for TIMELINE view)
     */
    private String timelineTitleField;

    /**
     * Resource grouping field — groups rows into swim lanes (for TIMELINE view)
     */
    private String timelineResourceField;

    /**
     * Default view mode: day, week, month (for TIMELINE view)
     */
    private String timelineDefaultView;

    /**
     * Whether to show weekends (for TIMELINE view)
     */
    private Boolean timelineShowWeekends;

    /**
     * Whether to highlight today (for TIMELINE view)
     */
    private Boolean timelineShowToday;

    // ==================== Form View Fields ====================

    /**
     * Form title shown at the top of the form (for FORM view)
     */
    private String formTitle;

    /**
     * Form description / subtitle (for FORM view)
     */
    private String formDescription;

    /**
     * Label for the submit button (for FORM view)
     */
    private String formSubmitLabel;

    /**
     * Message shown after successful submission (for FORM view)
     */
    private String formSuccessMessage;

    /**
     * Ordered list of field codes to show in the form (for FORM view, null = show all)
     */
    private List<String> formFields;

    // ==================== Tree Fields ====================

    /**
     * Parent ID field for building tree hierarchy (for TREE view)
     */
    private String treeParentField;

    /**
     * Title/name field to display for each node (for TREE view)
     */
    private String treeTitleField;

    /**
     * Fields to show in each tree node row (for TREE view)
     */
    private List<String> treeDisplayFields;

    /**
     * Kanban card field display configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KanbanCardFieldConfig {
        private String field;
        private String label;
        /** Display type: text, number, date, tag, avatar */
        private String type;
    }

    /**
     * Kanban column aggregation configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KanbanAggregationConfig {
        private String field;
        /** Aggregation function: COUNT, SUM, AVG, MIN, MAX */
        private String function;
        private String label;
    }

    /**
     * Column configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ColumnConfig {
        /**
         * Field code
         */
        private String fieldCode;

        /**
         * Whether column is visible
         */
        private Boolean visible;

        /**
         * Column width in pixels
         */
        private Integer width;

        /**
         * Column display order
         */
        private Integer order;

        /**
         * Whether column is frozen (pinned)
         */
        private Boolean frozen;

        /**
         * Frozen position: left, right
         */
        private String frozenPosition;
    }

    /**
     * Sort configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SortConfig {
        /**
         * Field code to sort by
         */
        private String fieldCode;

        /**
         * Sort direction: ASC, DESC
         */
        private String direction;

        /**
         * Sort priority (for multi-column sort)
         */
        private Integer priority;
    }

    /**
     * Filter configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilterConfig {
        /**
         * Field code to filter
         */
        private String fieldCode;

        /**
         * Filter operator: eq, ne, gt, gte, lt, lte, like, in, between, isNull, isNotNull
         */
        private String operator;

        /**
         * Filter value (can be single value, array, or range object)
         */
        private Object value;

        /**
         * Logic operator to combine with previous filter: AND, OR
         */
        private String logic;

        /**
         * Filter group for complex conditions
         */
        private String group;
    }

    /**
     * Toolbar action configuration.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolbarActionConfig {
        /**
         * Action code from DSL or built-in action code.
         */
        private String code;

        /**
         * Whether the action is visible.
         */
        private Boolean visible;

        /**
         * Whether the action is pinned directly in the toolbar.
         */
        private Boolean pinned;

        /**
         * Display order across pinned and overflow actions.
         */
        private Integer order;
    }

    /**
     * Group by configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroupByConfig {
        /**
         * Field code to group by
         */
        private String fieldCode;

        /**
         * Whether groups are collapsed by default
         */
        private Boolean collapsed;

        /**
         * Aggregation functions for group summary
         */
        private List<AggregationConfig> aggregations;
    }

    /**
     * Aggregation configuration for group summary
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AggregationConfig {
        /**
         * Field code to aggregate
         */
        private String fieldCode;

        /**
         * Aggregation function: COUNT, SUM, AVG, MIN, MAX
         */
        private String function;

        /**
         * Display label for aggregation result
         */
        private String label;
    }

    /**
     * Pagination configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaginationConfig {
        /**
         * Page size (rows per page)
         */
        private Integer pageSize;

        /**
         * Available page size options
         */
        private List<Integer> pageSizeOptions;
    }
}
