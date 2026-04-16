package com.auraboot.framework.plugin.dto.imports;

import com.auraboot.framework.plugin.dto.PluginManifest;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Extended plugin manifest that includes full configuration data for import.
 * This class extends the base PluginManifest with support for importing:
 * - Models (ab_meta_model)
 * - Fields (ab_meta_field)
 * - Model-Field bindings (ab_meta_model_field_binding)
 * - Commands (ab_command_definition)
 * - Binding rules (ab_binding_rule)
 * - Permissions (ab_permission)
 * - Roles (ab_role)
 * - Menus (ab_menu)
 * - Processes (BPMN)
 * - Pages (ab_page_schema)
 * - Dictionaries (ab_dict)
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PluginManifestExtended extends PluginManifest {

    // ==================== Localized Display Name ====================

    /**
     * Localized display names.
     */
    @JsonProperty("displayName:zh-CN")
    private String displayNameZhCN;

    @JsonProperty("displayName:en")
    private String displayNameEn;

    // ==================== Client-side Plugin Configuration ====================

    /**
     * Frontend client configuration.
     */
    private ClientConfig client;

    // ==================== Configuration Data ====================

    /**
     * Model definitions to import.
     */
    private List<ModelDefinitionDTO> models;

    /**
     * Field definitions to import.
     */
    private List<FieldDefinitionDTO> fields;

    /**
     * Model-field binding definitions.
     */
    private List<ModelFieldBindingDTO> modelFieldBindings;

    /**
     * Command definitions to import.
     */
    private List<CommandDefinitionDTO> commands;

    /**
     * Binding rule definitions (standalone, not embedded in commands).
     */
    private List<BindingRuleDTO> bindingRules;

    /**
     * Permission definitions to import.
     */
    private List<PermissionDefinitionDTO> permissions;

    /**
     * Role definitions to import.
     */
    private List<RoleDefinitionDTO> roles;

    /**
     * Menu definitions to import.
     */
    private List<MenuDefinitionDTO> menus;

    /**
     * Process definitions to import.
     */
    private List<ProcessDefinitionDTO> processes;

    /**
     * Page schema definitions to import.
     */
    private List<PageSchemaDTO> pages;

    /**
     * Dictionary definitions to import.
     */
    private List<DictDefinitionDTO> dicts;

    /**
     * i18n resource definitions to import.
     */
    private List<I18nDefinitionDTO> i18nResources;

    /**
     * Named query definitions to import.
     */
    private List<NamedQueryDefinitionDTO> namedQueries;

    /**
     * Saved view definitions to import.
     */
    private List<SavedViewDefinitionDTO> savedViews;

    /**
     * Dashboard definitions to import from {@code config/dashboards/*.json}.
     * This is the first-class contract (Plan #8); no BlockToDashboardConverter step is needed.
     */
    private List<DashboardDefinitionDTO> dashboards;

    /**
     * Drools rule definitions to import (maps to {@code ab_bpm_rule}).
     */
    private List<BpmRuleDefinitionDTO> rules;

    /**
     * SLA config definitions to import (maps to {@code ab_sla_config}).
     */
    private List<SlaConfigDefinitionDTO> slaConfigs;

    // ==================== Directory-based Configuration ====================

    /**
     * Resource directory mappings for directory-based plugin structure.
     * Key: resource type (models, fields, bindings, dicts, commands, menus, permissions, roles, pages, processes)
     * Value: relative path to the directory containing the resources
     *
     * Example:
     * <pre>
     * {
     *   "models": "models/",
     *   "fields": "fields/",
     *   "bindings": "bindings/",
     *   "dicts": "dicts/",
     *   "commands": "commands/",
     *   "menus": "menus/",
     *   "permissions": "permissions/",
     *   "roles": "roles/",
     *   "pages": "pages/"
     * }
     * </pre>
     */
    private Map<String, String> resourceDirs;

    // ==================== Import Configuration ====================

    /**
     * Import options.
     */
    private ImportOptions importOptions;

    // ==================== Validation Methods ====================

    /**
     * Validate the extended manifest.
     */
    @Override
    @JsonIgnore
    public boolean isValid() {
        return super.isValid() && validateResources();
    }

    /**
     * Validate all resource definitions.
     */
    @JsonIgnore
    public boolean validateResources() {
        // Validate models
        if (models != null) {
            for (ModelDefinitionDTO model : models) {
                if (!model.isValid()) {
                    return false;
                }
            }
        }

        // Validate fields
        if (fields != null) {
            for (FieldDefinitionDTO field : fields) {
                if (!field.isValid()) {
                    return false;
                }
            }
        }

        // Validate model-field bindings
        if (modelFieldBindings != null) {
            for (ModelFieldBindingDTO binding : modelFieldBindings) {
                if (!binding.isValid()) {
                    return false;
                }
            }
        }

        // Validate commands
        if (commands != null) {
            for (CommandDefinitionDTO command : commands) {
                if (!command.isValid()) {
                    return false;
                }
            }
        }

        // Validate binding rules
        if (bindingRules != null) {
            for (BindingRuleDTO rule : bindingRules) {
                if (!rule.isValid()) {
                    return false;
                }
            }
        }

        // Validate permissions
        if (permissions != null) {
            for (PermissionDefinitionDTO permission : permissions) {
                if (!permission.isValid()) {
                    return false;
                }
            }
        }

        // Validate roles
        if (roles != null) {
            for (RoleDefinitionDTO role : roles) {
                if (!role.isValid()) {
                    return false;
                }
            }
        }

        // Validate menus
        if (menus != null) {
            for (MenuDefinitionDTO menu : menus) {
                if (!menu.isValid()) {
                    return false;
                }
            }
        }

        // Validate processes
        if (processes != null) {
            for (ProcessDefinitionDTO process : processes) {
                if (!process.isValid()) {
                    return false;
                }
            }
        }

        // Validate named queries
        if (namedQueries != null) {
            for (NamedQueryDefinitionDTO namedQuery : namedQueries) {
                if (!namedQuery.isValid()) {
                    return false;
                }
            }
        }

        // Validate pages
        if (pages != null) {
            for (PageSchemaDTO page : pages) {
                if (!page.isValid()) {
                    return false;
                }
            }
        }

        // Validate dicts
        if (dicts != null) {
            for (DictDefinitionDTO dict : dicts) {
                if (!dict.isValid()) {
                    return false;
                }
            }
        }

        // Validate saved views
        if (savedViews != null) {
            for (SavedViewDefinitionDTO savedView : savedViews) {
                if (!savedView.isValid()) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Get all validation errors.
     */
    @JsonIgnore
    public List<String> getValidationErrors() {
        List<String> errors = new ArrayList<>();

        if (getPluginId() == null || getPluginId().isBlank()) {
            errors.add("pluginId is required");
        }
        if (getNamespace() == null || getNamespace().isBlank()) {
            errors.add("namespace is required");
        }
        if (getVersion() == null || getVersion().isBlank()) {
            errors.add("version is required");
        }

        // Validate models
        if (models != null) {
            for (int i = 0; i < models.size(); i++) {
                ModelDefinitionDTO model = models.get(i);
                if (!model.isValid()) {
                    errors.add("models[" + i + "]: code is required");
                }
            }
        }

        // Validate fields
        if (fields != null) {
            for (int i = 0; i < fields.size(); i++) {
                FieldDefinitionDTO field = fields.get(i);
                if (field.getCode() == null || field.getCode().isBlank()) {
                    errors.add("fields[" + i + "]: code is required");
                }
                if (field.getDataType() == null || field.getDataType().isBlank()) {
                    errors.add("fields[" + i + "]: dataType is required");
                }
            }
        }

        // Validate model-field bindings
        if (modelFieldBindings != null) {
            for (int i = 0; i < modelFieldBindings.size(); i++) {
                ModelFieldBindingDTO binding = modelFieldBindings.get(i);
                if (binding.getModelCode() == null || binding.getModelCode().isBlank()) {
                    errors.add("modelFieldBindings[" + i + "]: modelCode is required");
                }
                if (binding.getFieldCode() == null || binding.getFieldCode().isBlank()) {
                    errors.add("modelFieldBindings[" + i + "]: fieldCode is required");
                }
            }
        }

        // Validate ENTITY/VIEW models must have field bindings
        if (models != null) {
            // Collect all model codes that have bindings
            java.util.Set<String> modelsWithBindings = new java.util.HashSet<>();
            if (modelFieldBindings != null) {
                for (ModelFieldBindingDTO binding : modelFieldBindings) {
                    if (binding.getModelCode() != null) {
                        modelsWithBindings.add(binding.getModelCode());
                    }
                }
            }

            // Check each ENTITY/VIEW model has at least one field binding
            for (int i = 0; i < models.size(); i++) {
                ModelDefinitionDTO model = models.get(i);
                String modelType = model.getModelType();

                // ENTITY and VIEW models require field bindings
                if ("entity".equalsIgnoreCase(modelType) || "view".equalsIgnoreCase(modelType)) {
                    String modelCode = model.getCode();
                    if (modelCode != null && !modelsWithBindings.contains(modelCode)) {
                        errors.add("models[" + i + "] (" + modelCode + "): " + modelType +
                                " model requires at least one field binding in modelFieldBindings");
                    }
                }
            }
        }

        // Validate commands
        if (commands != null) {
            for (int i = 0; i < commands.size(); i++) {
                CommandDefinitionDTO command = commands.get(i);
                if (command.getCode() == null || command.getCode().isBlank()) {
                    errors.add("commands[" + i + "]: code is required");
                }
                if (command.getModelCode() == null || command.getModelCode().isBlank()) {
                    errors.add("commands[" + i + "]: modelCode is required");
                }
            }
        }

        // Validate processes
        if (processes != null) {
            for (int i = 0; i < processes.size(); i++) {
                ProcessDefinitionDTO process = processes.get(i);
                if (process.getKey() == null || process.getKey().isBlank()) {
                    errors.add("processes[" + i + "]: key is required");
                }
                if (process.getBpmnFile() == null && process.getBpmnContent() == null && process.getDesignerJson() == null) {
                    errors.add("processes[" + i + "]: bpmnFile, bpmnContent or designerJson is required");
                }
            }
        }

        // Validate named queries
        if (namedQueries != null) {
            for (int i = 0; i < namedQueries.size(); i++) {
                NamedQueryDefinitionDTO namedQuery = namedQueries.get(i);
                if (namedQuery.getCode() == null || namedQuery.getCode().isBlank()) {
                    errors.add("namedQueries[" + i + "]: code is required");
                }
                if (namedQuery.getFromSql() == null || namedQuery.getFromSql().isBlank()) {
                    errors.add("namedQueries[" + i + "]: fromSql is required");
                }
            }
        }

        return errors;
    }

    /**
     * Get validation warnings (unknown fields detected during deserialization).
     */
    @JsonIgnore
    public List<String> getValidationWarnings() {
        List<String> warnings = new ArrayList<>();

        collectUnknownFieldWarnings(warnings, "models", models, ModelDefinitionDTO::getUnknownFields, ModelDefinitionDTO::getCode);
        collectUnknownFieldWarnings(warnings, "fields", fields, FieldDefinitionDTO::getUnknownFields, FieldDefinitionDTO::getCode);
        collectUnknownFieldWarnings(warnings, "modelFieldBindings", modelFieldBindings, ModelFieldBindingDTO::getUnknownFields,
                b -> b.getModelCode() + "." + b.getFieldCode());
        collectUnknownFieldWarnings(warnings, "commands", commands, CommandDefinitionDTO::getUnknownFields, CommandDefinitionDTO::getCode);
        collectUnknownFieldWarnings(warnings, "bindingRules", bindingRules, BindingRuleDTO::getUnknownFields, BindingRuleDTO::getCommandCode);
        collectUnknownFieldWarnings(warnings, "permissions", permissions, PermissionDefinitionDTO::getUnknownFields, PermissionDefinitionDTO::getCode);
        collectUnknownFieldWarnings(warnings, "roles", roles, RoleDefinitionDTO::getUnknownFields, RoleDefinitionDTO::getCode);
        collectUnknownFieldWarnings(warnings, "menus", menus, MenuDefinitionDTO::getUnknownFields, MenuDefinitionDTO::getCode);
        collectUnknownFieldWarnings(warnings, "pages", pages, PageSchemaDTO::getUnknownFields, PageSchemaDTO::getPageKey);
        collectUnknownFieldWarnings(warnings, "dicts", dicts, DictDefinitionDTO::getUnknownFields, DictDefinitionDTO::getCode);
        collectUnknownFieldWarnings(warnings, "namedQueries", namedQueries,
                NamedQueryDefinitionDTO::getUnknownFields, NamedQueryDefinitionDTO::getCode);
        collectUnknownFieldWarnings(warnings, "savedViews", savedViews,
                SavedViewDefinitionDTO::getUnknownFields, SavedViewDefinitionDTO::getUniqueKey);

        return warnings;
    }

    private <T> void collectUnknownFieldWarnings(List<String> warnings, String resourceType, List<T> items,
                                                  Function<T, Map<String, Object>> unknownFieldsGetter,
                                                  Function<T, String> codeGetter) {
        if (items == null) return;
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            Map<String, Object> unknownFields = unknownFieldsGetter.apply(item);
            if (unknownFields != null && !unknownFields.isEmpty()) {
                String code = codeGetter.apply(item);
                String identifier = code != null ? code : String.valueOf(i);
                warnings.add(resourceType + "[" + identifier + "]: unknown fields " + unknownFields.keySet()
                        + " will be silently ignored");
            }
        }
    }

    /**
     * Get effective display name.
     */
    @JsonIgnore
    public String getEffectiveDisplayName() {
        if (displayNameZhCN != null && !displayNameZhCN.isBlank()) {
            return displayNameZhCN;
        }
        if (displayNameEn != null && !displayNameEn.isBlank()) {
            return displayNameEn;
        }
        return getDisplayName() != null ? getDisplayName() : getPluginId();
    }

    /**
     * Check if the manifest has any resources to import.
     */
    @JsonIgnore
    public boolean hasResources() {
        return (models != null && !models.isEmpty())
                || (fields != null && !fields.isEmpty())
                || (modelFieldBindings != null && !modelFieldBindings.isEmpty())
                || (commands != null && !commands.isEmpty())
                || (bindingRules != null && !bindingRules.isEmpty())
                || (permissions != null && !permissions.isEmpty())
                || (roles != null && !roles.isEmpty())
                || (menus != null && !menus.isEmpty())
                || (processes != null && !processes.isEmpty())
                || (pages != null && !pages.isEmpty())
                || (dicts != null && !dicts.isEmpty())
                || (i18nResources != null && !i18nResources.isEmpty())
                || (namedQueries != null && !namedQueries.isEmpty())
                || (savedViews != null && !savedViews.isEmpty())
                || (dashboards != null && !dashboards.isEmpty())
                || (rules != null && !rules.isEmpty())
                || (slaConfigs != null && !slaConfigs.isEmpty());
    }

    /**
     * Get resource count summary.
     */
    @JsonIgnore
    public Map<String, Integer> getResourceCounts() {
        return Map.ofEntries(
                Map.entry("models", models != null ? models.size() : 0),
                Map.entry("fields", fields != null ? fields.size() : 0),
                Map.entry("modelFieldBindings", modelFieldBindings != null ? modelFieldBindings.size() : 0),
                Map.entry("commands", commands != null ? commands.size() : 0),
                Map.entry("bindingRules", bindingRules != null ? bindingRules.size() : 0),
                Map.entry("permissions", permissions != null ? permissions.size() : 0),
                Map.entry("roles", roles != null ? roles.size() : 0),
                Map.entry("menus", menus != null ? menus.size() : 0),
                Map.entry("processes", processes != null ? processes.size() : 0),
                Map.entry("pages", pages != null ? pages.size() : 0),
                Map.entry("dicts", dicts != null ? dicts.size() : 0),
                Map.entry("i18nResources", i18nResources != null ? i18nResources.size() : 0),
                Map.entry("namedQueries", namedQueries != null ? namedQueries.size() : 0),
                Map.entry("savedViews", savedViews != null ? savedViews.size() : 0),
                Map.entry("dashboards", dashboards != null ? dashboards.size() : 0),
                Map.entry("rules", rules != null ? rules.size() : 0),
                Map.entry("slaConfigs", slaConfigs != null ? slaConfigs.size() : 0)
        );
    }

    // ==================== Sanitization ====================

    /**
     * Remove JSON "comment objects" from all resource lists.
     * A comment object is an entry where the identifying field (code/key) is null
     * and all captured unknown fields start with '_' (e.g., _note, _comment, _deprecated).
     * These are a common JSON documentation pattern but are not valid resource definitions.
     */
    public void sanitize() {
        if (models != null) models.removeIf(m -> isCommentObject(m.getCode(), m.getUnknownFields()));
        if (fields != null) fields.removeIf(f -> isCommentObject(f.getCode(), f.getUnknownFields()));
        if (modelFieldBindings != null) modelFieldBindings.removeIf(b -> isCommentObject(b.getModelCode(), b.getUnknownFields()));
        if (commands != null) commands.removeIf(c -> isCommentObject(c.getCode(), c.getUnknownFields()));
        if (bindingRules != null) bindingRules.removeIf(r -> isCommentObject(r.getCommandCode(), r.getUnknownFields()));
        if (permissions != null) permissions.removeIf(p -> isCommentObject(p.getCode(), p.getUnknownFields()));
        if (roles != null) roles.removeIf(r -> isCommentObject(r.getCode(), r.getUnknownFields()));
        if (menus != null) menus.removeIf(m -> isCommentObject(m.getCode(), m.getUnknownFields()));
        if (processes != null) processes.removeIf(p -> isCommentObject(p.getKey(), p.getUnknownFields()));
        if (pages != null) pages.removeIf(p -> isCommentObject(p.getPageKey(), p.getUnknownFields()));
        if (dicts != null) dicts.removeIf(d -> isCommentObject(d.getCode(), d.getUnknownFields()));
        if (namedQueries != null) namedQueries.removeIf(n -> isCommentObject(n.getCode(), n.getUnknownFields()));
        if (savedViews != null) savedViews.removeIf(s -> isCommentObject(s.getUniqueKey(), s.getUnknownFields()));
        if (dashboards != null) dashboards.removeIf(d -> isCommentObject(d.getCode(), d.getUnknownFields()));
        if (i18nResources != null) i18nResources.removeIf(i -> i.getKey() == null || i.getKey().isBlank());
    }

    /**
     * Check if a deserialized entry is a JSON comment object.
     * Returns true if the identifier is null AND all unknown fields are _-prefixed metadata.
     */
    private static boolean isCommentObject(String identifier, Map<String, Object> unknownFields) {
        if (identifier != null && !identifier.isBlank()) {
            return false;
        }
        // No identifier and has _-prefixed unknown fields → comment object
        if (unknownFields != null && !unknownFields.isEmpty()) {
            return unknownFields.keySet().stream().allMatch(k -> k.startsWith("_"));
        }
        // No identifier and no data at all → empty/invalid object, also filter out
        return true;
    }

    // ==================== Nested Classes ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClientConfig {
        /**
         * Whether frontend plugin is enabled.
         */
        private Boolean enabled;

        /**
         * CDN URL for frontend assets (optional, for CDN mode).
         */
        private String url;

        /**
         * List of frontend components provided by this plugin.
         */
        private List<ComponentConfig> components;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComponentConfig {
        /**
         * Component type: form-field, page-action, dashboard-widget, sidebar-menu.
         */
        private String type;

        /**
         * Component name.
         */
        private String name;

        /**
         * Description.
         */
        private String description;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImportOptions {
        /**
         * Conflict resolution strategy: ERROR, SKIP, OVERWRITE.
         */
        private String conflictStrategy;

        /**
         * Whether to validate references between resources.
         */
        private Boolean validateReferences;

        /**
         * Whether to auto-deploy BPM processes.
         */
        private Boolean autoDeployProcesses;

        /**
         * Whether to create permissions for imported resources.
         */
        private Boolean createResourcePermissions;

        /**
         * Whether to publish models after import.
         */
        private Boolean autoPublishModels;

        /**
         * Whether to publish fields after import.
         */
        private Boolean autoPublishFields;

        /**
         * Whether to publish commands after import.
         */
        private Boolean autoPublishCommands;

        /**
         * Whether to publish pages after import.
         */
        private Boolean autoPublishPages;
    }
}
