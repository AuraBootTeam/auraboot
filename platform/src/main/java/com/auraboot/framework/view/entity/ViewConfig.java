package com.auraboot.framework.view.entity;

import io.swagger.v3.oas.annotations.media.Schema;

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
    @Schema(nullable = true)
    private List<ColumnConfig> columns;

    /**
     * Sort configurations
     */
    @Schema(nullable = true)
    private List<SortConfig> sorts;

    /**
     * Filter configurations
     */
    @Schema(nullable = true)
    private List<FilterConfig> filters;

    /**
     * Group by configurations
     */
    @Schema(nullable = true)
    private List<GroupByConfig> groupBy;

    /**
     * Pagination configuration
     */
    @Schema(nullable = true)
    private PaginationConfig pagination;

    /**
     * Whether to show row numbers
     */
    @Schema(nullable = true)
    private Boolean showRowNumbers;

    /**
     * Table density: compact, default, comfortable (legacy)
     */
    @Schema(nullable = true)
    private String density;

    /**
     * Row height preset: short (32px), medium (44px), tall (60px), extra-tall (80px)
     */
    @Schema(nullable = true)
    private String rowHeight;

    /**
     * Conditional formatting rules for TABLE view.
     * Each rule: fieldCode + operator + value → style (backgroundColor, textColor, bold).
     */
    @Schema(nullable = true)
    private List<java.util.Map<String, Object>> conditionalFormats;

    /**
     * Toolbar action button configurations for TABLE view.
     */
    @Schema(nullable = true)
    private List<ToolbarActionConfig> toolbarActions;

    /**
     * Saved-view lifecycle metadata. This keeps plugin preset ownership and
     * copy/lock semantics inside the JSONB config so older rows remain schema-compatible.
     */
    @Schema(nullable = true)
    private Meta meta;

    // ==================== Kanban Fields ====================

    /**
     * Field used to group cards into columns (for KANBAN view)
     */
    @Schema(nullable = true)
    private String groupByField;

    /**
     * Field used as card ID (for KANBAN view), defaults to 'id'
     */
    @Schema(nullable = true)
    private String idField;

    /**
     * Field used as card title (for KANBAN view)
     */
    @Schema(nullable = true)
    private String titleField;

    /**
     * Field used as card description (for KANBAN view)
     */
    @Schema(nullable = true)
    private String descriptionField;

    /**
     * Fields to display on kanban cards
     */
    @Schema(nullable = true)
    private List<KanbanCardFieldConfig> cardFields;

    /**
     * Aggregation configurations for kanban columns
     */
    @Schema(nullable = true)
    private List<KanbanAggregationConfig> kanbanAggregations;

    /**
     * Whether kanban cards can be dragged between columns
     */
    @Schema(nullable = true)
    private Boolean draggable;

    /**
     * Whether to show card count in kanban column headers
     */
    @Schema(nullable = true)
    private Boolean showCount;

    /**
     * Whether to show aggregation values in kanban column headers
     */
    @Schema(nullable = true)
    private Boolean showAggregations;

    /**
     * Optional dict code that backs the groupByField. When present, the
     * kanban renders the full set of dict enum values as columns (in dict
     * sortOrder), even for stages with zero cards — matching CRM-style
     * pipeline visibility (Salesforce/HubSpot). Without this, only stages
     * present in the data render. See backlog 2026-05-08 Gap 2.
     */
    @Schema(nullable = true)
    private String groupByDictCode;

    /**
     * Optional terminal-stage hint for kanban views. Maps `won` / `lost`
     * arrays of dict values that should receive terminal visual treatment
     * regardless of dict-extension metadata. Stored as a JSON object so
     * plugin views can express both buckets independently of the dict.
     */
    @Schema(nullable = true)
    private java.util.Map<String, java.util.List<String>> terminalStages;

    // ==================== Calendar Fields ====================

    /**
     * Date field for calendar event start (for CALENDAR view)
     */
    @Schema(nullable = true)
    private String calendarDateField;

    /**
     * Title field for calendar event display (for CALENDAR view)
     */
    @Schema(nullable = true)
    private String calendarTitleField;

    /**
     * End date field for multi-day events (for CALENDAR view)
     */
    @Schema(nullable = true)
    private String calendarEndDateField;

    /**
     * Color-by field for event color coding (for CALENDAR view)
     */
    @Schema(nullable = true)
    private String calendarColorField;

    /**
     * Default calendar view: dayGridMonth, timeGridWeek, listWeek
     */
    @Schema(nullable = true)
    private String calendarDefaultView;

    // ==================== Gallery Fields ====================

    /**
     * Field containing image URL (for GALLERY view)
     */
    @Schema(nullable = true)
    private String galleryImageField;

    /**
     * Field used as card title (for GALLERY view)
     */
    @Schema(nullable = true)
    private String galleryTitleField;

    /**
     * Field used as card description (for GALLERY view)
     */
    @Schema(nullable = true)
    private String galleryDescriptionField;

    /**
     * Number of grid columns: 2, 3, 4, 6 (for GALLERY view)
     */
    @Schema(nullable = true)
    private Integer galleryColumns;

    /**
     * Image aspect ratio: square, 4:3, 16:9, auto (for GALLERY view)
     */
    @Schema(nullable = true)
    private String galleryAspectRatio;

    /**
     * Whether to show title overlay on cards (for GALLERY view)
     */
    @Schema(nullable = true)
    private Boolean galleryShowTitle;

    /**
     * Whether to show description on cards (for GALLERY view)
     */
    @Schema(nullable = true)
    private Boolean galleryShowDescription;

    /**
     * Additional fields to display on gallery cards (for GALLERY view)
     */
    @Schema(nullable = true)
    private List<String> galleryDisplayFields;

    // ==================== Gantt Fields ====================

    /**
     * Start date field (for GANTT view)
     */
    @Schema(nullable = true)
    private String ganttStartDateField;

    /**
     * End date field (for GANTT view)
     */
    @Schema(nullable = true)
    private String ganttEndDateField;

    /**
     * Title field for task bars (for GANTT view)
     */
    @Schema(nullable = true)
    private String ganttTitleField;

    /**
     * Progress field 0-100 (for GANTT view)
     */
    @Schema(nullable = true)
    private String ganttProgressField;

    /**
     * Dependency field - comma-separated IDs (for GANTT view)
     */
    @Schema(nullable = true)
    private String ganttDependencyField;

    /**
     * Default view mode: Day, Week, Month (for GANTT view)
     */
    @Schema(nullable = true)
    private String ganttDefaultView;

    // ==================== Timeline Fields ====================

    /**
     * Start date/time field for timeline bars (for TIMELINE view)
     */
    @Schema(nullable = true)
    private String timelineStartField;

    /**
     * End date/time field for timeline bars (for TIMELINE view)
     */
    @Schema(nullable = true)
    private String timelineEndField;

    /**
     * Title field for timeline bars (for TIMELINE view)
     */
    @Schema(nullable = true)
    private String timelineTitleField;

    /**
     * Resource grouping field — groups rows into swim lanes (for TIMELINE view)
     */
    @Schema(nullable = true)
    private String timelineResourceField;

    /**
     * Default view mode: day, week, month (for TIMELINE view)
     */
    @Schema(nullable = true)
    private String timelineDefaultView;

    /**
     * Whether to show weekends (for TIMELINE view)
     */
    @Schema(nullable = true)
    private Boolean timelineShowWeekends;

    /**
     * Whether to highlight today (for TIMELINE view)
     */
    @Schema(nullable = true)
    private Boolean timelineShowToday;

    // ==================== Form View Fields ====================

    /**
     * Form title shown at the top of the form (for FORM view)
     */
    @Schema(nullable = true)
    private String formTitle;

    /**
     * Form description / subtitle (for FORM view)
     */
    @Schema(nullable = true)
    private String formDescription;

    /**
     * Label for the submit button (for FORM view)
     */
    @Schema(nullable = true)
    private String formSubmitLabel;

    /**
     * Message shown after successful submission (for FORM view)
     */
    @Schema(nullable = true)
    private String formSuccessMessage;

    /**
     * Ordered list of field codes to show in the form (for FORM view, null = show all)
     */
    @Schema(nullable = true)
    private List<String> formFields;

    // ==================== Tree Fields ====================

    /**
     * Parent ID field for building tree hierarchy (for TREE view)
     */
    @Schema(nullable = true)
    private String treeParentField;

    /**
     * Title/name field to display for each node (for TREE view)
     */
    @Schema(nullable = true)
    private String treeTitleField;

    /**
     * Fields to show in each tree node row (for TREE view)
     */
    @Schema(nullable = true)
    private List<String> treeDisplayFields;

    /**
     * SavedView metadata used by plugin imports and copy/edit policies.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Meta {
        @Schema(nullable = true)
        private String viewKey;
        @Schema(nullable = true)
        private String managedBy;
        @Schema(nullable = true)
        private Boolean locked;
        @Schema(nullable = true)
        private Boolean allowUserCopy;
        @Schema(nullable = true)
        private Boolean allowUserOverride;
        @Schema(nullable = true)
        private String originViewPid;
        @Schema(nullable = true)
        private String originPresetKey;
        @Schema(nullable = true)
        private String capabilityStatus;
        /** Stable reason codes already submitted by the view configuration editor. */
        @Schema(nullable = true)
        private List<String> capabilityReasonCodes;
        /** Runtime overlay replay state: CURRENT, REBASED, STALE, or UNTRACKED. */
        @Schema(nullable = true)
        private String overlayStatus;
        /** Stable server-owned reasons explaining replay/degradation. */
        @Schema(nullable = true)
        private List<String> overlayReasonCodes;
        /** Metadata-only paths ignored or restored while replaying the overlay. */
        @Schema(nullable = true)
        private List<String> overlayStalePaths;
        /** Page identity against which this overlay was last saved. */
        @Schema(nullable = true)
        private String basePagePid;
        /** Stable page key used when an immutable Application Release has no tenant-local PID. */
        @Schema(nullable = true)
        private String basePageKey;
        /** Immutable authoring release identity, null for legacy PageSchema baselines. */
        @Schema(nullable = true)
        private String baseReleasePid;
        /** Active channel version observed when the overlay was saved. */
        @Schema(nullable = true)
        private Long baseChannelVersion;
        /** Runtime snapshot checksum observed when the overlay was saved. */
        @Schema(nullable = true)
        private String baseSnapshotChecksum;
        /** Server-derived field identities used for upgrade-safe replay. */
        @Schema(nullable = true)
        private List<String> baseFieldCodes;
        /** Server-derived action identities used for upgrade-safe replay. */
        @Schema(nullable = true)
        private List<String> baseActionCodes;
        /** When true, this view is pinned as a one-click quick-filter chip. */
        @Schema(nullable = true)
        private Boolean pinnedAsQuickFilter;
        /** Icon (emoji or known token) rendered on the quick-filter chip. */
        @Schema(nullable = true)
        private String quickFilterIcon;
        /** Ascending display order among pinned view chips. */
        @Schema(nullable = true)
        private Integer quickFilterOrder;
        @Schema(nullable = true)
        private List<CollaboratorAcl> collaborators;
    }

    /**
     * Per-view collaborator ACL stored inside viewConfig.meta.
     *
     * principalType is currently "user"; permission is one of view/save/manage.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CollaboratorAcl {
        @Schema(nullable = true)
        private String principalType;
        @Schema(nullable = true)
        private String principalPid;
        @Schema(nullable = true)
        private String permission;
    }

    /**
     * Kanban card field display configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KanbanCardFieldConfig {
        @Schema(nullable = true)
        private String field;
        /** Dictionary binding used to render enum values as localized business labels. */
        @Schema(nullable = true)
        private String dictCode;
        /**
         * Display label: either a plain string or a localized map ({@code {"zh-CN": "…", "en": "…"}}).
         * Typed as Object so localized maps survive the jsonb round-trip — a strict String here made
         * Jackson reject the whole ViewConfig and silently drop saved views carrying map labels
         * (WMS-UX-02). Renderers resolve maps via getLocalizedText.
         */
        private Object label;
        /** Display type: text, number, date, tag, avatar */
        @Schema(nullable = true)
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
        @Schema(nullable = true)
        private String field;
        /** Aggregation function: COUNT, SUM, AVG, MIN, MAX */
        @Schema(nullable = true)
        private String function;
        /**
         * Display label: plain string or localized map ({zh-CN, en}) — widened from
         * String so bilingual aggregation labels survive the jsonb round trip
         * (same failure mode as KanbanCardFieldConfig.label, WMS-UX-02).
         */
        private Object label;
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
        @Schema(nullable = true)
        private String fieldCode;

        /**
         * Whether column is visible
         */
        @Schema(nullable = true)
        private Boolean visible;

        /**
         * Column width in pixels
         */
        @Schema(nullable = true)
        private Integer width;

        /**
         * Column display order
         */
        @Schema(nullable = true)
        private Integer order;

        /**
         * Whether column is frozen (pinned)
         */
        @Schema(nullable = true)
        private Boolean frozen;

        /**
         * Frozen position: left, right
         */
        @Schema(nullable = true)
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
        @Schema(nullable = true)
        private String fieldCode;

        /**
         * Sort direction: ASC, DESC
         */
        @Schema(nullable = true)
        private String direction;

        /**
         * Sort priority (for multi-column sort)
         */
        @Schema(nullable = true)
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
        @Schema(nullable = true)
        private String fieldCode;

        /**
         * Filter operator: eq, ne, gt, gte, lt, lte, like, in, between, isNull, isNotNull
         */
        @Schema(nullable = true)
        private String operator;

        /**
         * Filter value (can be single value, array, or range object)
         */
        private Object value;

        /**
         * Whether the filter value is resolved from a supported runtime expression.
         */
        @Schema(nullable = true)
        private Boolean isExpression;

        /**
         * Runtime expression such as ${system.currentUser}.
         */
        @Schema(nullable = true)
        private String expression;

        /**
         * Logic operator to combine with previous filter: AND, OR
         */
        @Schema(nullable = true)
        private String logic;

        /**
         * Filter group for complex conditions
         */
        @Schema(nullable = true)
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
        @Schema(nullable = true)
        private String code;

        /**
         * Whether the action is visible.
         */
        @Schema(nullable = true)
        private Boolean visible;

        /**
         * Whether the action is pinned directly in the toolbar.
         */
        @Schema(nullable = true)
        private Boolean pinned;

        /**
         * Display order across pinned and overflow actions.
         */
        @Schema(nullable = true)
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
        @Schema(nullable = true)
        private String fieldCode;

        /**
         * Whether groups are collapsed by default
         */
        @Schema(nullable = true)
        private Boolean collapsed;

        /**
         * Aggregation functions for group summary
         */
        @Schema(nullable = true)
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
        @Schema(nullable = true)
        private String fieldCode;

        /**
         * Aggregation function: COUNT, SUM, AVG, MIN, MAX
         */
        @Schema(nullable = true)
        private String function;

        /**
         * Display label for aggregation result
         */
        @Schema(nullable = true)
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
        @Schema(nullable = true)
        private Integer pageSize;

        /**
         * Available page size options
         */
        @Schema(nullable = true)
        private List<Integer> pageSizeOptions;
    }
}
