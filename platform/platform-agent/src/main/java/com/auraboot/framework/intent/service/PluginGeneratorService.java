package com.auraboot.framework.intent.service;

import com.auraboot.framework.intent.dto.IntentAnalysisResult;
import com.auraboot.framework.intent.dto.IntentAnalysisResult.*;
import com.auraboot.framework.intent.dto.PluginGenerateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Generates a complete plugin configuration from an IntentAnalysisResult.
 * Produces models.json, fields.json, bindings.json, commands.json,
 * pages.json, menus.json, i18n.json, permissions.json.
 *
 * Uses a template-based approach: structural patterns are deterministic,
 * while business details come from the analysis result.
 */
@Service
public class PluginGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(PluginGeneratorService.class);

    /**
     * Generate plugin configuration from analysis result.
     */
    public PluginGenerateResult generate(IntentAnalysisResult analysis, String pluginCode, String pluginName) {
        if (analysis == null || analysis.getEntities() == null || analysis.getEntities().isEmpty()) {
            throw new IllegalArgumentException("Analysis must contain at least one entity");
        }
        if (pluginCode == null || pluginCode.isBlank()) {
            throw new IllegalArgumentException("Plugin code must not be empty");
        }

        List<Map<String, Object>> models = generateModels(analysis, pluginCode);
        List<Map<String, Object>> fields = generateFields(analysis, pluginCode);
        List<Map<String, Object>> bindings = generateBindings(analysis, pluginCode);
        List<Map<String, Object>> commands = generateCommands(analysis, pluginCode);
        List<Map<String, Object>> pages = generatePages(analysis, pluginCode);
        List<Map<String, Object>> menus = generateMenus(analysis, pluginCode);
        Map<String, Map<String, String>> i18n = generateI18n(analysis, pluginCode);
        List<Map<String, Object>> permissions = generatePermissions(analysis, pluginCode);

        int fieldCount = fields.size();
        int commandCount = commands.size();
        int pageCount = pages.size();

        Map<String, Object> configs = new LinkedHashMap<>();
        configs.put("models.json", models);
        configs.put("fields.json", fields);
        configs.put("bindings.json", bindings);
        configs.put("commands.json", commands);
        configs.put("pages.json", pages);
        configs.put("menus.json", menus);
        configs.put("i18n.json", i18n);
        configs.put("permissions.json", permissions);

        return PluginGenerateResult.builder()
                .pluginCode(pluginCode)
                .pluginName(pluginName)
                .configs(configs)
                .modelCount(models.size())
                .fieldCount(fieldCount)
                .commandCount(commandCount)
                .pageCount(pageCount)
                .summary("Generated plugin '%s' with %d models, %d fields, %d commands, %d pages"
                        .formatted(pluginName, models.size(), fieldCount, commandCount, pageCount))
                .build();
    }

    // ---- Models ----

    private List<Map<String, Object>> generateModels(IntentAnalysisResult analysis, String pluginCode) {
        List<Map<String, Object>> models = new ArrayList<>();
        for (EntityDef entity : analysis.getEntities()) {
            String modelCode = pluginCode.replace("-", "_") + "_" + entity.getCode();
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("code", modelCode);
            model.put("name", entity.getName());
            model.put("description", entity.getDescription());
            model.put("tableName", "mt_" + modelCode);
            model.put("type", "dynamic");
            model.put("source", "plugin");
            model.put("pluginCode", pluginCode);
            models.add(model);
        }
        return models;
    }

    // ---- Fields ----

    private List<Map<String, Object>> generateFields(IntentAnalysisResult analysis, String pluginCode) {
        List<Map<String, Object>> fields = new ArrayList<>();
        String prefix = pluginCode.replace("-", "_");

        for (EntityDef entity : analysis.getEntities()) {
            String modelCode = prefix + "_" + entity.getCode();
            int sortOrder = 10;

            for (FieldDef field : entity.getFields()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("modelCode", modelCode);
                f.put("code", field.getCode());
                f.put("name", field.getName());
                f.put("fieldType", mapFieldType(field.getType()));
                f.put("columnName", field.getCode());
                f.put("required", field.isRequired());
                f.put("sortOrder", sortOrder);
                f.put("source", "plugin");
                f.put("pluginCode", pluginCode);

                // Extension for ENUM or REFERENCE
                if ("enum".equalsIgnoreCase(field.getType()) && field.getEnumValues() != null) {
                    Map<String, Object> ext = new LinkedHashMap<>();
                    ext.put("dictCode", field.getCode() + "_dict");
                    f.put("extension", ext);
                }
                if ("reference".equalsIgnoreCase(field.getType()) && field.getReferenceModel() != null) {
                    Map<String, Object> ext = new LinkedHashMap<>();
                    ext.put("refModelCode", prefix + "_" + field.getReferenceModel());
                    f.put("extension", ext);
                }

                fields.add(f);
                sortOrder += 10;
            }
        }
        return fields;
    }

    private String mapFieldType(String type) {
        if (type == null) return "string";
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "string" -> "string";
            case "integer" -> "integer";
            case "decimal" -> "decimal";
            case "date" -> "date";
            case "datetime" -> "datetime";
            case "boolean" -> "boolean";
            case "text" -> "text";
            case "reference" -> "reference";
            case "enum" -> "enum";
            default -> "string";
        };
    }

    // ---- Bindings ----

    private List<Map<String, Object>> generateBindings(IntentAnalysisResult analysis, String pluginCode) {
        List<Map<String, Object>> bindings = new ArrayList<>();
        String prefix = pluginCode.replace("-", "_");

        for (EntityDef entity : analysis.getEntities()) {
            String modelCode = prefix + "_" + entity.getCode();
            for (FieldDef field : entity.getFields()) {
                Map<String, Object> binding = new LinkedHashMap<>();
                binding.put("modelCode", modelCode);
                binding.put("fieldCode", field.getCode());
                binding.put("columnName", field.getCode());
                binding.put("source", "plugin");
                binding.put("pluginCode", pluginCode);
                bindings.add(binding);
            }
        }
        return bindings;
    }

    // ---- Commands ----

    private List<Map<String, Object>> generateCommands(IntentAnalysisResult analysis, String pluginCode) {
        List<Map<String, Object>> commands = new ArrayList<>();
        String prefix = pluginCode.replace("-", "_");

        for (EntityDef entity : analysis.getEntities()) {
            String modelCode = prefix + "_" + entity.getCode();
            // CREATE command
            commands.add(buildCommand(modelCode, "create", "create", pluginCode));
            // UPDATE command
            commands.add(buildCommand(modelCode, "update", "update", pluginCode));
            // DELETE command
            commands.add(buildCommand(modelCode, "delete", "delete", pluginCode));
        }

        // State transition commands from state machines
        if (analysis.getStateMachines() != null) {
            for (StateMachineDef sm : analysis.getStateMachines()) {
                String modelCode = prefix + "_" + sm.getEntityCode();
                if (sm.getTransitions() != null) {
                    for (TransitionDef tr : sm.getTransitions()) {
                        Map<String, Object> cmd = new LinkedHashMap<>();
                        cmd.put("modelCode", modelCode);
                        cmd.put("code", modelCode + "_" + tr.getAction().toLowerCase().replace(" ", "_"));
                        cmd.put("name", tr.getAction());
                        cmd.put("type", "update");
                        cmd.put("source", "plugin");
                        cmd.put("pluginCode", pluginCode);

                        // Guard: only allow transition from specific state
                        Map<String, Object> guard = new LinkedHashMap<>();
                        guard.put("fieldCode", sm.getFieldCode());
                        guard.put("operator", "equals");
                        guard.put("value", tr.getFrom());
                        cmd.put("guard", guard);

                        // Effect: set new state
                        Map<String, Object> effect = new LinkedHashMap<>();
                        effect.put("fieldCode", sm.getFieldCode());
                        effect.put("value", tr.getTo());
                        cmd.put("effects", List.of(effect));

                        commands.add(cmd);
                    }
                }
            }
        }

        return commands;
    }

    private Map<String, Object> buildCommand(String modelCode, String action, String type, String pluginCode) {
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("modelCode", modelCode);
        cmd.put("code", modelCode + "_" + action);
        cmd.put("name", capitalize(action));
        cmd.put("type", type);
        cmd.put("source", "plugin");
        cmd.put("pluginCode", pluginCode);
        return cmd;
    }

    // ---- Pages ----

    private List<Map<String, Object>> generatePages(IntentAnalysisResult analysis, String pluginCode) {
        List<Map<String, Object>> pages = new ArrayList<>();
        String prefix = pluginCode.replace("-", "_");

        for (EntityDef entity : analysis.getEntities()) {
            String modelCode = prefix + "_" + entity.getCode();
            String pathSegment = entity.getCode().replace("_", "-");

            // List page
            Map<String, Object> listPage = new LinkedHashMap<>();
            listPage.put("code", modelCode + "_list");
            listPage.put("name", entity.getName() + " List");
            listPage.put("modelCode", modelCode);
            listPage.put("pageType", "list");
            listPage.put("routePath", "/" + pluginCode + "/" + pathSegment);
            listPage.put("source", "plugin");
            listPage.put("pluginCode", pluginCode);
            pages.add(listPage);

            // Detail page
            Map<String, Object> detailPage = new LinkedHashMap<>();
            detailPage.put("code", modelCode + "_detail");
            detailPage.put("name", entity.getName() + " Detail");
            detailPage.put("modelCode", modelCode);
            detailPage.put("pageType", "detail");
            detailPage.put("routePath", "/" + pluginCode + "/" + pathSegment + "/:id");
            detailPage.put("source", "plugin");
            detailPage.put("pluginCode", pluginCode);
            pages.add(detailPage);
        }
        return pages;
    }

    // ---- Menus ----

    private List<Map<String, Object>> generateMenus(IntentAnalysisResult analysis, String pluginCode) {
        List<Map<String, Object>> menus = new ArrayList<>();

        // Top-level menu group
        Map<String, Object> rootMenu = new LinkedHashMap<>();
        rootMenu.put("code", pluginCode + "_menu");
        rootMenu.put("name", analysis.getEntities().get(0).getName()); // Use first entity as group name
        rootMenu.put("type", "group");
        rootMenu.put("icon", "FolderIcon");
        rootMenu.put("sortOrder", 100);
        rootMenu.put("source", "plugin");
        rootMenu.put("pluginCode", pluginCode);
        menus.add(rootMenu);

        // Child menu items for each entity
        int sortOrder = 10;
        String prefix = pluginCode.replace("-", "_");
        for (EntityDef entity : analysis.getEntities()) {
            String pathSegment = entity.getCode().replace("_", "-");
            Map<String, Object> menuItem = new LinkedHashMap<>();
            menuItem.put("code", prefix + "_" + entity.getCode() + "_menu");
            menuItem.put("parentCode", pluginCode + "_menu");
            menuItem.put("name", entity.getName());
            menuItem.put("type", "menu");
            menuItem.put("path", "/" + pluginCode + "/" + pathSegment);
            menuItem.put("permissionCode", prefix + "_" + entity.getCode() + ":LIST");
            menuItem.put("sortOrder", sortOrder);
            menuItem.put("source", "plugin");
            menuItem.put("pluginCode", pluginCode);
            menus.add(menuItem);
            sortOrder += 10;
        }
        return menus;
    }

    // ---- I18n ----

    private Map<String, Map<String, String>> generateI18n(IntentAnalysisResult analysis, String pluginCode) {
        Map<String, String> en = new LinkedHashMap<>();
        Map<String, String> zh = new LinkedHashMap<>();
        String prefix = pluginCode.replace("-", "_");

        for (EntityDef entity : analysis.getEntities()) {
            String modelCode = prefix + "_" + entity.getCode();

            en.put("model." + modelCode + ".label", entity.getName());
            zh.put("model." + modelCode + ".label", entity.getName());

            for (FieldDef field : entity.getFields()) {
                en.put("field." + modelCode + "." + field.getCode() + ".label", field.getName());
                zh.put("field." + modelCode + "." + field.getCode() + ".label", field.getName());
            }
        }

        // Menu labels
        en.put("menu." + pluginCode + "_menu.label", analysis.getEntities().get(0).getName());
        zh.put("menu." + pluginCode + "_menu.label", analysis.getEntities().get(0).getName());

        for (EntityDef entity : analysis.getEntities()) {
            String menuKey = "menu." + prefix + "_" + entity.getCode() + "_menu.label";
            en.put(menuKey, entity.getName());
            zh.put(menuKey, entity.getName());
        }

        Map<String, Map<String, String>> i18n = new LinkedHashMap<>();
        i18n.put("en", en);
        i18n.put("zh-CN", zh);
        return i18n;
    }

    // ---- Permissions ----

    private List<Map<String, Object>> generatePermissions(IntentAnalysisResult analysis, String pluginCode) {
        List<Map<String, Object>> permissions = new ArrayList<>();
        String prefix = pluginCode.replace("-", "_");

        for (EntityDef entity : analysis.getEntities()) {
            String modelCode = prefix + "_" + entity.getCode();
            for (String action : List.of("list", "create", "update", "delete")) {
                Map<String, Object> perm = new LinkedHashMap<>();
                perm.put("code", modelCode + ":" + action);
                perm.put("name", entity.getName() + " " + action);
                perm.put("type", "dynamic");
                perm.put("source", "plugin");
                perm.put("pluginCode", pluginCode);
                permissions.add(perm);
            }
        }
        return permissions;
    }

    // ---- Util ----

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }
}
