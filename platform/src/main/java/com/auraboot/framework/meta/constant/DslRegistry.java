package com.auraboot.framework.meta.constant;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Unified DSL Capability Registry — 25 closed enums across 6 sections.
 * <p>
 * Replaces scattered hardcoded Sets with a single source of truth for
 * all DSL vocabulary used in validation, code-generation, and agent tooling.
 */
public final class DslRegistry {

    private DslRegistry() {}

    /** Common interface for all DSL enums. */
    public interface DslEnum {
        String code();
        String label();
        String since();
    }

    // ════════════════════════════════════════════════════════════
    // 4.1  Data Modeling Layer
    // ════════════════════════════════════════════════════════════

    public enum ModelType implements DslEnum {
        ENTITY("entity", "Standard entity model", "1.0"),
        VIEW("view", "Read-only view model", "1.0"),
        TREE("tree", "Hierarchical tree model", "1.0"),
        VIRTUAL("virtual", "Virtual model without table", "1.1");

        private final String code, label, since;
        ModelType(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(ModelType::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum DataType implements DslEnum {
        STRING("string", "String", "1.0"),
        TEXT("text", "Long text", "1.0"),
        INTEGER("integer", "Integer", "1.0"),
        DECIMAL("decimal", "Decimal", "1.0"),
        BOOLEAN("boolean", "Boolean", "1.0"),
        DATE("date", "Date", "1.0"),
        DATETIME("datetime", "Date and time", "1.0"),
        JSON("json", "JSON object", "1.0"),
        ENUM("enum", "Enumeration", "1.0"),
        REFERENCE("reference", "Reference to another model", "1.0"),
        COMPUTED("computed", "Computed field", "1.0"),
        AI_TEXT("ai_text", "AI-generated text", "1.1"),
        MONEY("money", "Multi-currency amount", "1.1");

        private final String code, label, since;
        DataType(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(DataType::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum FieldType implements DslEnum {
        INPUT("input", "Text input", "1.0"),
        NUMBER("number", "Number input", "1.0"),
        SELECT("select", "Dropdown select", "1.0"),
        RADIO("radio", "Radio group", "1.0"),
        CHECKBOX("checkbox", "Checkbox group", "1.0"),
        DATE("date", "Date picker", "1.0"),
        DATETIME("datetime", "Datetime picker", "1.0"),
        TEXTAREA("textarea", "Text area", "1.0"),
        RICH_TEXT("rich_text", "Rich text editor", "1.0"),
        SWITCH("switch", "Toggle switch", "1.0"),
        UPLOAD("upload", "File upload", "1.0"),
        CUSTOM("custom", "Custom component", "1.0"),
        AI_INPUT("ai_input", "AI-assisted input", "1.1");

        private final String code, label, since;
        FieldType(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(FieldType::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum RelationType implements DslEnum {
        REFERENCE("reference", "Foreign-key reference", "1.0"),
        MANY_TO_MANY("many_to_many", "Many-to-many junction", "1.0"),
        ONE_TO_ONE("one_to_one", "One-to-one mapping", "1.0");

        private final String code, label, since;
        RelationType(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(RelationType::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum FieldSemanticRole implements DslEnum {
        IDENTIFIER("identifier", "Unique identifier", "1.1"),
        NAME("name", "Display name", "1.1"),
        STATUS("status", "Status field", "1.1"),
        AMOUNT("amount", "Monetary amount", "1.1"),
        DATE_ROLE("date", "Date semantic", "1.1"),
        REFERENCE_ROLE("reference", "Reference semantic", "1.1"),
        DESCRIPTION("description", "Description text", "1.1"),
        METRIC("metric", "Numeric metric", "1.1");

        private final String code, label, since;
        FieldSemanticRole(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(FieldSemanticRole::code).collect(Collectors.toUnmodifiableSet()); }
    }

    // ════════════════════════════════════════════════════════════
    // 4.2  Command Execution Layer
    // ════════════════════════════════════════════════════════════

    public enum CommandType implements DslEnum {
        CREATE("create", "Create record", "1.0"),
        UPDATE("update", "Update record", "1.0"),
        DELETE("delete", "Delete record", "1.0"),
        STATE_TRANSITION("state_transition", "State transition", "1.0"),
        QUERY("query", "Query records", "1.0"),
        BATCH("batch", "Batch operation", "1.0"),
        CUSTOM("custom", "Custom command", "1.0"),
        ACTION("action", "Standalone action", "1.1");

        private final String code, label, since;
        CommandType(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(CommandType::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum AutoSetStrategy implements DslEnum {
        CURRENT_USER("current_user", "Current user ID", "1.0"),
        CURRENT_USER_PID("current_user_pid", "Current user PID", "1.0"),
        CURRENT_USERNAME("current_username", "Current username", "1.0"),
        CURRENT_DATE("current_date", "Current business date (tenant-local)", "1.2"),
        CURRENT_DATETIME("current_datetime", "Current UTC timestamp", "1.2"),
        CURRENT_TENANT("current_tenant", "Current tenant ID", "1.0"),
        UUID("uuid", "Generated UUID", "1.0"),
        SEQUENCE("sequence", "Auto-increment sequence", "1.0"),
        EXPRESSION("expression", "SpEL expression", "1.0"),
        FIXED_VALUE("fixed_value", "Fixed constant (always overrides payload)", "1.0"),
        DEFAULT_VALUE("default_value", "Default constant (only when payload omits field)", "1.3"),
        AUTO_GENERATE("auto_generate", "Auto-generated value", "1.0"),
        COPY_FIELD("copy_field", "Copy from another field", "1.0"),
        FIELD_MAP("field_map", "Map from source field", "1.0");

        private final String code, label, since;
        AutoSetStrategy(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(AutoSetStrategy::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum PreconditionOperator implements DslEnum {
        EQ("EQ", "Equals", "1.0"),
        NE("NE", "Not equals", "1.0"),
        GT("GT", "Greater than", "1.0"),
        GE("GE", "Greater or equal", "1.0"),
        LT("LT", "Less than", "1.0"),
        LE("LE", "Less or equal", "1.0"),
        IN("IN", "In set", "1.0"),
        NOT_IN("not_in", "Not in set", "1.0"),
        IS_NULL("is_null", "Is null", "1.0"),
        IS_NOT_NULL("is_not_null", "Is not null", "1.0"),
        BETWEEN("between", "Between range", "1.0"),
        LIKE("like", "Pattern match", "1.0"),
        NOT_LIKE("not_like", "Not pattern match", "1.0"),
        CONTAINS("contains", "Contains substring", "1.0"),
        STARTS_WITH("starts_with", "Starts with", "1.0"),
        ENDS_WITH("ends_with", "Ends with", "1.0");

        private final String code, label, since;
        PreconditionOperator(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(PreconditionOperator::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum AggregateFunction implements DslEnum {
        SUM("sum", "Sum", "1.0"),
        COUNT("count", "Count", "1.0"),
        AVG("avg", "Average", "1.0"),
        MAX("max", "Maximum", "1.0"),
        MIN("min", "Minimum", "1.0");

        private final String code, label, since;
        AggregateFunction(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(AggregateFunction::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum RiskLevel implements DslEnum {
        L0("L0", "Safe read — no side effects", "1.1"),
        L1("L1", "Internal write — single object", "1.1"),
        L2("L2", "Cross-object write — cascade", "1.1"),
        L3("L3", "External side effect", "1.1"),
        L4("L4", "Irreversible operation", "1.1");

        private final String code, label, since;
        RiskLevel(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(RiskLevel::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum LinkageActionType implements DslEnum {
        SHOW("show", "Show field", "1.0"),
        HIDE("hide", "Hide field", "1.0"),
        ENABLE("enable", "Enable field", "1.0"),
        DISABLE("disable", "Disable field", "1.0"),
        SET_REQUIRED("setRequired", "Set required", "1.0"),
        SET_VALUE("setValue", "Set value", "1.0"),
        SET_OPTIONS("setOptions", "Set options", "1.0"),
        VALIDATE("validate", "Trigger validation", "1.0");

        private final String code, label, since;
        LinkageActionType(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(LinkageActionType::code).collect(Collectors.toUnmodifiableSet()); }
    }

    // ════════════════════════════════════════════════════════════
    // 4.3  Page / View Layer
    // ════════════════════════════════════════════════════════════

    public enum PageKind implements DslEnum {
        LIST("list", "List page", "1.0"),
        FORM("form", "Form page", "1.0"),
        DETAIL("detail", "Detail page", "1.0"),
        DASHBOARD("dashboard", "Dashboard page", "1.0"),
        COMPOSITE("composite", "Composite page", "1.0");

        private final String code, label, since;
        PageKind(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(PageKind::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum BlockType implements DslEnum {
        FORM("form", "Form block", "1.0"),
        FORM_SECTION("form-section", "Form section", "1.0"),
        FORM_BUTTONS("form-buttons", "Form buttons", "1.0"),
        FORM_WIZARD("form-wizard", "Form wizard", "1.0"),
        TABLE("table", "Table block", "1.0"),
        FILTERS("filters", "Filter block", "1.0"),
        TOOLBAR("toolbar", "Toolbar block", "1.0"),
        DESCRIPTION("description", "Description block", "1.0"),
        CHART("chart", "Chart block", "1.0"),
        TABS("tabs", "Tab container", "1.0"),
        SUB_TABLE("sub-table", "Sub-table block", "1.0"),
        MONTHLY_GRID("monthly-grid", "Monthly grid", "1.0"),
        STAT_CARD("stat-card", "Statistics card", "1.1"),
        AI_FILL_BANNER("ai-fill-banner", "AI fill banner", "1.1"),
        CUSTOM("custom", "Custom block", "1.0");

        private final String code, label, since;
        BlockType(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(BlockType::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum PageSuffix implements DslEnum {
        LIST("_list", "List page suffix", "1.0"),
        FORM("_form", "Form page suffix", "1.0"),
        DETAIL("_detail", "Detail page suffix", "1.0"),
        DASHBOARD("_dashboard", "Dashboard suffix", "1.0"),
        KANBAN("_kanban", "Kanban suffix", "1.0"),
        GANTT("_gantt", "Gantt suffix", "1.0"),
        CALENDAR("_calendar", "Calendar suffix", "1.0"),
        GALLERY("_gallery", "Gallery suffix", "1.0");

        private final String code, label, since;
        PageSuffix(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(PageSuffix::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum SavedViewType implements DslEnum {
        TABLE("table", "Table view", "1.0"),
        KANBAN("kanban", "Kanban board", "1.0"),
        CALENDAR("calendar", "Calendar view", "1.0"),
        GALLERY("gallery", "Gallery view", "1.0"),
        GANTT("gantt", "Gantt chart", "1.0"),
        TREE("tree", "Tree view", "1.0");

        private final String code, label, since;
        SavedViewType(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(SavedViewType::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum SavedViewScope implements DslEnum {
        PERSONAL("personal", "Personal view", "1.0"),
        TEAM("team", "Team-shared view", "1.0"),
        GLOBAL("global", "Global view", "1.0");

        private final String code, label, since;
        SavedViewScope(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(SavedViewScope::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum ChartType implements DslEnum {
        // Original 6 types (v1.0)
        NUMBER("number", "Number card", "1.0"),
        BAR("bar", "Bar chart", "1.0"),
        LINE("line", "Line chart", "1.0"),
        PIE("pie", "Pie chart", "1.0"),
        TABLE_CHART("table", "Table chart", "1.0"),
        GAUGE("gauge", "Gauge chart", "1.0"),
        // 18 additional types aligned with frontend SharedChartFactory (v1.2)
        AREA("area", "Area chart", "1.2"),
        RADAR("radar", "Radar chart", "1.2"),
        SCATTER("scatter", "Scatter plot", "1.2"),
        FUNNEL("funnel", "Funnel chart", "1.2"),
        HEATMAP("heatmap", "Heatmap chart", "1.2"),
        TREEMAP("treemap", "Treemap chart", "1.2"),
        MAP("map", "Geographic map chart", "1.2"),
        SPC("spc", "SPC control chart", "1.2"),
        PARETO("pareto", "Pareto chart", "1.2"),
        GANTT("gantt", "Gantt chart", "1.2"),
        NUMBER_CARD("number-card", "Number card widget", "1.2"),
        PROGRESS("progress", "Progress indicator", "1.2"),
        LEADERBOARD("leaderboard", "Leaderboard chart", "1.2"),
        RICH_TEXT_CHART("rich-text", "Rich text widget", "1.2"),
        IMAGE("image", "Image widget", "1.2"),
        IFRAME("iframe", "Iframe embed widget", "1.2"),
        COUNTDOWN("countdown", "Countdown timer widget", "1.2"),
        CALENDAR("calendar", "Calendar chart", "1.2");

        private final String code, label, since;
        ChartType(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(ChartType::code).collect(Collectors.toUnmodifiableSet()); }
    }

    // ════════════════════════════════════════════════════════════
    // 4.4  Automation & BPM Layer
    // ════════════════════════════════════════════════════════════

    public enum AutomationTrigger implements DslEnum {
        ON_RECORD_CREATE("on_record_create", "On record create", "1.0"),
        ON_RECORD_UPDATE("on_record_update", "On record update", "1.0"),
        ON_FIELD_CHANGE("on_field_change", "On field change", "1.0"),
        ON_STATE_CHANGE("on_state_change", "On state change", "1.0"),
        SCHEDULED("scheduled", "Scheduled trigger", "1.0"),
        WEBHOOK("webhook", "Webhook trigger", "1.0"),
        ON_BPM_EVENT("on_bpm_event", "On BPM event", "1.1"),
        ON_INACTIVITY("on_inactivity", "On inactivity timeout", "1.1");

        private final String code, label, since;
        AutomationTrigger(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(AutomationTrigger::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum NotificationChannel implements DslEnum {
        IN_APP("in_app", "In-app notification", "1.0"),
        EMAIL("email", "Email notification", "1.0"),
        SMS("sms", "SMS notification", "1.0"),
        WEBHOOK("webhook", "Webhook callback", "1.0");

        private final String code, label, since;
        NotificationChannel(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(NotificationChannel::code).collect(Collectors.toUnmodifiableSet()); }
    }

    // ════════════════════════════════════════════════════════════
    // 4.5  Security & Governance Layer
    // ════════════════════════════════════════════════════════════

    public enum DataPermissionScope implements DslEnum {
        ALL("all", "All records", "1.0"),
        SELF("self", "Own records only", "1.0"),
        DEPARTMENT("department", "Department records", "1.0"),
        CUSTOM_SCOPE("custom", "Custom scope rule", "1.0");

        private final String code, label, since;
        DataPermissionScope(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(DataPermissionScope::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum DataPermissionMask implements DslEnum {
        HIDE("hide", "Hide field value", "1.0"),
        PARTIAL("partial", "Show partial value", "1.0"),
        HASH("hash", "Hash field value", "1.0"),
        CUSTOM_MASK("custom", "Custom mask rule", "1.0");

        private final String code, label, since;
        DataPermissionMask(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(DataPermissionMask::code).collect(Collectors.toUnmodifiableSet()); }
    }

    // ════════════════════════════════════════════════════════════
    // 4.6  Runtime Status (Tier 2)
    // ════════════════════════════════════════════════════════════

    public enum NamedQueryStatus implements DslEnum {
        DRAFT("draft", "Draft", "1.0"),
        PUBLISHED("published", "Published", "1.0"),
        DEPRECATED("deprecated", "Deprecated", "1.0"),
        ARCHIVED("archived", "Archived", "1.0"),
        ERROR("error", "Error state", "1.0");

        private final String code, label, since;
        NamedQueryStatus(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(NamedQueryStatus::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum AutomationLogStatus implements DslEnum {
        PENDING("pending", "Pending execution", "1.0"),
        RUNNING("running", "Currently running", "1.0"),
        SUCCESS("success", "Completed successfully", "1.0"),
        FAILED("failed", "Execution failed", "1.0"),
        CANCELLED("cancelled", "Cancelled", "1.0");

        private final String code, label, since;
        AutomationLogStatus(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(AutomationLogStatus::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum BpmTriggerType implements DslEnum {
        MANUAL("manual", "Manual trigger", "1.0"),
        AUTO("auto", "Automatic trigger", "1.0"),
        SCHEDULED("scheduled", "Scheduled trigger", "1.0"),
        API("api", "API trigger", "1.0");

        private final String code, label, since;
        BpmTriggerType(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(BpmTriggerType::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum BpmNodeIntervention implements DslEnum {
        APPROVE("approve", "Approve node", "1.0"),
        REJECT("reject", "Reject node", "1.0"),
        DELEGATE("delegate", "Delegate to another user", "1.0"),
        ADD_SIGN("add_sign", "Add co-signer", "1.0");

        private final String code, label, since;
        BpmNodeIntervention(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(BpmNodeIntervention::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum ChartDataSourceType implements DslEnum {
        NAMED_QUERY("namedQuery", "Named query data source", "1.0"),
        AGGREGATE("aggregate", "Aggregate data source", "1.0"),
        STATIC("static", "Static data source", "1.0");

        private final String code, label, since;
        ChartDataSourceType(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(ChartDataSourceType::code).collect(Collectors.toUnmodifiableSet()); }
    }

    public enum PluginResourceType implements DslEnum {
        MODEL("model", "Model resource", "1.0"),
        COMMAND("command", "Command resource", "1.0"),
        PAGE("page", "Page schema resource", "1.0"),
        DICT("dict", "Dictionary resource", "1.0"),
        NAMED_QUERY("named_query", "Named query resource", "1.0"),
        MENU("menu", "Menu resource", "1.0"),
        PERMISSION("permission", "Permission resource", "1.0"),
        ROLE("role", "Role resource", "1.0");

        private final String code, label, since;
        PluginResourceType(String code, String label, String since) { this.code = code; this.label = label; this.since = since; }
        @Override public String code() { return code; }
        @Override public String label() { return label; }
        @Override public String since() { return since; }
        public static Set<String> codes() { return Arrays.stream(values()).map(PluginResourceType::code).collect(Collectors.toUnmodifiableSet()); }
    }
}
