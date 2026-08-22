package com.auraboot.framework.agent.nlmodeling;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the deterministic list/form/detail fallback for a single generated model. */
final class NlModelingDefaultPages {

    private NlModelingDefaultPages() {
    }

    static List<Map<String, Object>> create(String plugin, String model, List<String> fieldCodes,
                                            Set<String> requiredCodes) {
        return new ArrayList<>(List.of(
                listPage(plugin, model, fieldCodes),
                formPage(plugin, model, fieldCodes, requiredCodes),
                detailPage(model, fieldCodes)));
    }

    private static Map<String, Object> listPage(String plugin, String model, List<String> fieldCodes) {
        List<Map<String, Object>> columns = new ArrayList<>();
        fieldCodes.forEach(field -> columns.add(map("field", field, "width", 160, "sortable", true)));
        columns.add(map("field", "actions", "isActionColumn", true,
                "label", "$i18n:common.actions", "buttons", List.of(
                        map("code", "edit", "action", "edit", "navigateTo", model + "_form",
                                "permissionCode", permission(model, "update"),
                                "label", "$i18n:common.button.edit"),
                        map("code", "delete", "action", "delete", "danger", true,
                                "commandCode", plugin + ":delete_" + model,
                                "permissionCode", permission(model, "delete"),
                                "label", "$i18n:common.button.delete"))));
        Map<String, Object> toolbar = map("id", model + "_toolbar", "blockType", "toolbar",
                "area", "toolbar", "buttons", List.of(
                        map("code", "create", "action", "create", "primary", true,
                                "permissionCode", permission(model, "create"),
                                "label", "$i18n:common.button.create")));
        Map<String, Object> table = map("id", model + "_table", "blockType", "table",
                "props", map("rowClickAction", "drawer"), "columns", columns,
                "searchFields", new ArrayList<>(fieldCodes), "area", "main");
        return page(model, "list", List.of(toolbar, table));
    }

    private static Map<String, Object> formPage(String plugin, String model, List<String> fieldCodes,
                                                Set<String> requiredCodes) {
        List<Map<String, Object>> fields = fields(fieldCodes, requiredCodes);
        Map<String, Object> section = map("id", "basic", "blockType", "form-section",
                "title", map("zh-CN", "Basic Information", "en-US", "Basic Information"),
                "fields", fields, "area", "main");
        Map<String, Object> buttons = map("id", "buttons", "blockType", "form-buttons",
                "area", "footer", "buttons", List.of(
                        map("code", "submit", "action", "save",
                                "commandCode", plugin + ":create_" + model,
                                "permissionCode", permission(model, "create"), "primary", true,
                                "label", "$i18n:common.button.submit"),
                        map("code", "cancel", "action", "cancel",
                                "label", "$i18n:common.button.cancel")));
        return page(model, "form", List.of(section, buttons));
    }

    private static Map<String, Object> detailPage(String model, List<String> fieldCodes) {
        Map<String, Object> toolbar = map("id", "action_bar", "blockType", "toolbar",
                "area", "header", "buttons", List.of(
                        map("code", "edit", "action", "edit",
                                "permissionCode", permission(model, "update"),
                                "label", "$i18n:common.button.edit")));
        Map<String, Object> section = map("id", "info", "blockType", "form-section",
                "area", "main", "readOnly", true,
                "title", map("zh-CN", "Details", "en-US", "Details"),
                "fields", fields(fieldCodes, Set.of()));
        return page(model, "detail", List.of(toolbar, section));
    }

    private static List<Map<String, Object>> fields(List<String> fieldCodes, Set<String> requiredCodes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String field : fieldCodes) {
            Map<String, Object> item = map("field", field, "colSpan", 6);
            if (requiredCodes.contains(field)) {
                item.put("required", true);
            }
            result.add(item);
        }
        return result;
    }

    private static Map<String, Object> page(String model, String kind, List<Map<String, Object>> blocks) {
        String suffix = Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
        String name = NlModelingService.humanize(model) + " " + suffix;
        return map("pageKey", model + "_" + kind, "name:zh-CN", name, "name:en", name,
                "kind", kind, "schemaVersion", 4, "modelCode", model,
                "title", map("zh-CN", name, "en", name), "layout", map("type", "stack"),
                "blocks", blocks);
    }

    private static String permission(String model, String action) {
        return "model." + model + "." + action;
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}

