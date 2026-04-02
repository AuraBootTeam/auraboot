package com.auraboot.framework.meta.constant;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for DslRegistry — 25 closed enums across 6 sections.
 */
class DslRegistryTest {

    // ── 4.1 Data Modeling Layer ──────────────────────────────────

    @Test
    void modelType_has4Values() {
        assertEquals(4, DslRegistry.ModelType.values().length);
        Set<String> codes = DslRegistry.ModelType.codes();
        assertTrue(codes.containsAll(Set.of("entity", "view", "tree", "virtual")));
    }

    @Test
    void dataType_has13Values() {
        assertEquals(13, DslRegistry.DataType.values().length);
        Set<String> codes = DslRegistry.DataType.codes();
        assertTrue(codes.containsAll(Set.of(
                "string", "text", "integer", "decimal", "boolean",
                "date", "datetime", "json", "enum", "reference",
                "computed", "ai_text", "money")));
    }

    @Test
    void fieldType_has13Values() {
        assertEquals(13, DslRegistry.FieldType.values().length);
        Set<String> codes = DslRegistry.FieldType.codes();
        assertTrue(codes.containsAll(Set.of(
                "input", "number", "select", "radio", "checkbox",
                "date", "datetime", "textarea", "rich_text", "switch",
                "upload", "custom", "ai_input")));
    }

    @Test
    void relationType_has3Values() {
        assertEquals(3, DslRegistry.RelationType.values().length);
        Set<String> codes = DslRegistry.RelationType.codes();
        assertTrue(codes.containsAll(Set.of("reference", "many_to_many", "one_to_one")));
    }

    @Test
    void fieldSemanticRole_has8Values() {
        assertEquals(8, DslRegistry.FieldSemanticRole.values().length);
        Set<String> codes = DslRegistry.FieldSemanticRole.codes();
        assertTrue(codes.containsAll(Set.of(
                "identifier", "name", "status", "amount",
                "date", "reference", "description", "metric")));
    }

    // ── 4.2 Command Execution Layer ─────────────────────────────

    @Test
    void commandType_has8Values() {
        assertEquals(8, DslRegistry.CommandType.values().length);
        Set<String> codes = DslRegistry.CommandType.codes();
        assertTrue(codes.containsAll(Set.of(
                "create", "update", "delete", "state_transition",
                "query", "batch", "custom", "action")));
    }

    @Test
    void autoSetStrategy_has13Values() {
        assertEquals(13, DslRegistry.AutoSetStrategy.values().length);
        Set<String> codes = DslRegistry.AutoSetStrategy.codes();
        assertTrue(codes.containsAll(Set.of(
                "current_user", "current_user_pid", "current_username",
                "current_date", "current_datetime", "current_tenant",
                "uuid", "sequence", "expression", "fixed_value",
                "auto_generate", "copy_field", "field_map")));
    }

    @Test
    void preconditionOperator_has16Values() {
        assertEquals(16, DslRegistry.PreconditionOperator.values().length);
        Set<String> codes = DslRegistry.PreconditionOperator.codes();
        assertTrue(codes.containsAll(Set.of(
                "EQ", "NE", "GT", "GE", "LT", "LE", "IN", "not_in",
                "is_null", "is_not_null", "between", "like", "not_like",
                "contains", "starts_with", "ends_with")));
    }

    @Test
    void aggregateFunction_has5Values() {
        assertEquals(5, DslRegistry.AggregateFunction.values().length);
        Set<String> codes = DslRegistry.AggregateFunction.codes();
        assertTrue(codes.containsAll(Set.of("sum", "count", "avg", "max", "min")));
    }

    @Test
    void riskLevel_has5Values() {
        assertEquals(5, DslRegistry.RiskLevel.values().length);
        assertEquals("L0", DslRegistry.RiskLevel.L0.code());
        assertEquals("Safe read — no side effects", DslRegistry.RiskLevel.L0.label());
        assertEquals("L4", DslRegistry.RiskLevel.L4.code());
        Set<String> codes = DslRegistry.RiskLevel.codes();
        assertTrue(codes.containsAll(Set.of("L0", "L1", "L2", "L3", "L4")));
    }

    @Test
    void linkageActionType_has8ValuesWithLowercaseCodes() {
        assertEquals(8, DslRegistry.LinkageActionType.values().length);
        Set<String> codes = DslRegistry.LinkageActionType.codes();
        assertTrue(codes.containsAll(Set.of(
                "show", "hide", "enable", "disable",
                "setRequired", "setValue", "setOptions", "validate")));
    }

    // ── 4.3 Page/View Layer ─────────────────────────────────────

    @Test
    void pageKind_has5Values() {
        assertEquals(5, DslRegistry.PageKind.values().length);
        Set<String> codes = DslRegistry.PageKind.codes();
        assertTrue(codes.containsAll(Set.of(
                "list", "form", "detail", "dashboard", "composite")));
    }

    @Test
    void blockType_has14Values() {
        assertEquals(14, DslRegistry.BlockType.values().length);
        Set<String> codes = DslRegistry.BlockType.codes();
        assertTrue(codes.containsAll(Set.of(
                "form", "form-section", "form-buttons", "form-wizard",
                "table", "filters", "toolbar", "description", "chart",
                "tabs", "sub-table", "monthly-grid", "stat-card", "custom")));
    }

    @Test
    void pageSuffix_has8Values() {
        assertEquals(8, DslRegistry.PageSuffix.values().length);
        Set<String> codes = DslRegistry.PageSuffix.codes();
        assertTrue(codes.containsAll(Set.of(
                "_list", "_form", "_detail", "_dashboard",
                "_kanban", "_gantt", "_calendar", "_gallery")));
    }

    @Test
    void savedViewType_has6Values() {
        assertEquals(6, DslRegistry.SavedViewType.values().length);
        Set<String> codes = DslRegistry.SavedViewType.codes();
        assertTrue(codes.containsAll(Set.of(
                "table", "kanban", "calendar", "gallery", "gantt", "tree")));
    }

    @Test
    void savedViewScope_has3Values() {
        assertEquals(3, DslRegistry.SavedViewScope.values().length);
        Set<String> codes = DslRegistry.SavedViewScope.codes();
        assertTrue(codes.containsAll(Set.of("personal", "team", "global")));
    }

    @Test
    void chartType_has24ValuesWithLowercaseCodes() {
        assertEquals(24, DslRegistry.ChartType.values().length);
        Set<String> codes = DslRegistry.ChartType.codes();
        // Original 6
        assertTrue(codes.containsAll(Set.of(
                "number", "bar", "line", "pie", "table", "gauge")));
        // 18 additional types added in v1.2, aligned with frontend SharedChartFactory
        assertTrue(codes.containsAll(Set.of(
                "area", "radar", "scatter", "funnel", "heatmap", "treemap",
                "map", "spc", "pareto", "gantt", "number-card", "progress",
                "leaderboard", "rich-text", "image", "iframe", "countdown", "calendar")));
    }

    // ── 4.4 Automation & BPM Layer ──────────────────────────────

    @Test
    void automationTrigger_has8Values() {
        assertEquals(8, DslRegistry.AutomationTrigger.values().length);
        Set<String> codes = DslRegistry.AutomationTrigger.codes();
        assertTrue(codes.containsAll(Set.of(
                "on_record_create", "on_record_update", "on_field_change",
                "on_state_change", "scheduled", "webhook",
                "on_bpm_event", "on_inactivity")));
    }

    @Test
    void notificationChannel_has4Values() {
        assertEquals(4, DslRegistry.NotificationChannel.values().length);
        Set<String> codes = DslRegistry.NotificationChannel.codes();
        assertTrue(codes.containsAll(Set.of("in_app", "email", "sms", "webhook")));
    }

    // ── 4.5 Security & Governance Layer ─────────────────────────

    @Test
    void dataPermissionScope_has4Values() {
        assertEquals(4, DslRegistry.DataPermissionScope.values().length);
        Set<String> codes = DslRegistry.DataPermissionScope.codes();
        assertTrue(codes.containsAll(Set.of("all", "self", "department", "custom")));
    }

    @Test
    void dataPermissionMask_has4Values() {
        assertEquals(4, DslRegistry.DataPermissionMask.values().length);
        Set<String> codes = DslRegistry.DataPermissionMask.codes();
        assertTrue(codes.containsAll(Set.of("hide", "partial", "hash", "custom")));
    }

    // ── 4.6 Runtime Status (Tier 2) ─────────────────────────────

    @Test
    void namedQueryStatus_has5Values() {
        assertEquals(5, DslRegistry.NamedQueryStatus.values().length);
        Set<String> codes = DslRegistry.NamedQueryStatus.codes();
        assertTrue(codes.containsAll(Set.of(
                "draft", "published", "deprecated", "archived", "error")));
    }

    @Test
    void automationLogStatus_has5Values() {
        assertEquals(5, DslRegistry.AutomationLogStatus.values().length);
        Set<String> codes = DslRegistry.AutomationLogStatus.codes();
        assertTrue(codes.containsAll(Set.of(
                "pending", "running", "success", "failed", "cancelled")));
    }

    @Test
    void bpmTriggerType_has4Values() {
        assertEquals(4, DslRegistry.BpmTriggerType.values().length);
        Set<String> codes = DslRegistry.BpmTriggerType.codes();
        assertTrue(codes.containsAll(Set.of("manual", "auto", "scheduled", "api")));
    }

    @Test
    void bpmNodeIntervention_has4Values() {
        assertEquals(4, DslRegistry.BpmNodeIntervention.values().length);
        Set<String> codes = DslRegistry.BpmNodeIntervention.codes();
        assertTrue(codes.containsAll(Set.of("approve", "reject", "delegate", "add_sign")));
    }

    @Test
    void chartDataSourceType_has3ValuesWithCamelCaseCodes() {
        assertEquals(3, DslRegistry.ChartDataSourceType.values().length);
        Set<String> codes = DslRegistry.ChartDataSourceType.codes();
        assertTrue(codes.containsAll(Set.of("namedQuery", "aggregate", "static")));
    }

    @Test
    void pluginResourceType_has8Values() {
        assertEquals(8, DslRegistry.PluginResourceType.values().length);
        Set<String> codes = DslRegistry.PluginResourceType.codes();
        assertTrue(codes.containsAll(Set.of(
                "model", "command", "page", "dict",
                "named_query", "menu", "permission", "role")));
    }

    // ── Meta test ───────────────────────────────────────────────

    @Test
    void totalNestedEnumCount_is25() {
        long count = Arrays.stream(DslRegistry.class.getDeclaredClasses())
                .filter(Class::isEnum)
                .count();
        assertEquals(27, count, "DslRegistry must contain exactly 27 nested enums");
    }

    @Test
    void allEnumsImplementDslEnum() {
        Arrays.stream(DslRegistry.class.getDeclaredClasses())
                .filter(Class::isEnum)
                .forEach(enumClass -> {
                    assertTrue(
                            DslRegistry.DslEnum.class.isAssignableFrom(enumClass),
                            enumClass.getSimpleName() + " must implement DslEnum");
                });
    }

    @Test
    void allEnumsHaveSinceVersion() {
        Arrays.stream(DslRegistry.class.getDeclaredClasses())
                .filter(Class::isEnum)
                .forEach(enumClass -> {
                    Object[] constants = enumClass.getEnumConstants();
                    for (Object c : constants) {
                        DslRegistry.DslEnum dsl = (DslRegistry.DslEnum) c;
                        assertNotNull(dsl.since(), enumClass.getSimpleName() + "." + c + " must have since()");
                        assertFalse(dsl.since().isBlank(), enumClass.getSimpleName() + "." + c + " since() must not be blank");
                    }
                });
    }
}
