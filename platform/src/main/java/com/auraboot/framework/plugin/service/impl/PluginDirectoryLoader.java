package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.exception.PluginException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Loads plugin configuration from a directory-based structure.
 *
 * Expected directory structure:
 * <pre>
 * plugin-dir/
 * ├── plugin.json              # Main plugin metadata with resourceDirs
 * ├── models/                  # Model definitions
 * │   ├── pm_project.json
 * │   └── ...
 * ├── fields/                  # Field definitions
 * │   ├── project_code.json
 * │   └── ...
 * ├── bindings/                # Model-field binding definitions (grouped by model)
 * │   ├── pm_project.json      # Contains array of bindings for pm_project
 * │   └── ...
 * ├── dicts/                   # Dictionary definitions
 * ├── commands/                # Command definitions
 * ├── menus/                   # Menu definitions
 * ├── permissions/             # Permission definitions
 * ├── roles/                   # Role definitions
 * └── pages/                   # Page DSL definitions
 * </pre>
 */
@Slf4j
@Component
public class PluginDirectoryLoader {

    private final ObjectMapper objectMapper;

    public PluginDirectoryLoader() {
        this.objectMapper = createObjectMapper();
    }

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    /**
     * Load plugin manifest from a directory.
     *
     * @param pluginDir path to the plugin directory
     * @return extended manifest with all resources loaded
     */
    public PluginManifestExtended loadFromDirectory(Path pluginDir) {
        if (!Files.isDirectory(pluginDir)) {
            throw new PluginException("Plugin path is not a directory: " + pluginDir);
        }

        Path manifestPath = pluginDir.resolve("plugin.json");
        if (!Files.exists(manifestPath)) {
            throw new PluginException("plugin.json not found in: " + pluginDir);
        }

        try {
            // Load main manifest
            PluginManifestExtended manifest = objectMapper.readValue(
                    manifestPath.toFile(), PluginManifestExtended.class);

            // Load resources from directories based on resourceDirs config
            Map<String, String> resourceDirs = manifest.getResourceDirs();
            if (resourceDirs != null && !resourceDirs.isEmpty()) {
                loadResourcesFromDirs(pluginDir, manifest, resourceDirs);
            }

            log.info("Loaded plugin from directory: {} v{}, resources: {}",
                    manifest.getPluginId(), manifest.getVersion(), manifest.getResourceCounts());

            return manifest;

        } catch (IOException e) {
            throw new PluginException("Failed to load plugin from directory: " + e.getMessage(), e);
        }
    }

    /**
     * Check if a directory contains a valid plugin structure.
     */
    public boolean isValidPluginDirectory(Path pluginDir) {
        return Files.exists(pluginDir.resolve("plugin.json"));
    }

    private void loadResourcesFromDirs(Path pluginDir, PluginManifestExtended manifest,
                                       Map<String, String> resourceDirs) throws IOException {
        // Load models
        if (resourceDirs.containsKey("models")) {
            List<ModelDefinitionDTO> models = loadResourceList(
                    pluginDir.resolve(resourceDirs.get("models")), ModelDefinitionDTO.class);
            if (!models.isEmpty()) {
                manifest.setModels(mergeList(manifest.getModels(), models));
            }
        }

        // Load fields
        if (resourceDirs.containsKey("fields")) {
            List<FieldDefinitionDTO> fields = loadResourceList(
                    pluginDir.resolve(resourceDirs.get("fields")), FieldDefinitionDTO.class);
            if (!fields.isEmpty()) {
                manifest.setFields(mergeList(manifest.getFields(), fields));
            }
        }

        // Load bindings (modelFieldBindings key)
        String bindingsKey = resourceDirs.containsKey("modelFieldBindings") ? "modelFieldBindings" : "bindings";
        if (resourceDirs.containsKey(bindingsKey)) {
            List<ModelFieldBindingDTO> bindings = loadResourceList(
                    pluginDir.resolve(resourceDirs.get(bindingsKey)), ModelFieldBindingDTO.class);
            if (!bindings.isEmpty()) {
                manifest.setModelFieldBindings(mergeList(manifest.getModelFieldBindings(), bindings));
            }
        }

        // Load dicts
        if (resourceDirs.containsKey("dicts")) {
            List<DictDefinitionDTO> dicts = loadResourceList(
                    pluginDir.resolve(resourceDirs.get("dicts")), DictDefinitionDTO.class);
            if (!dicts.isEmpty()) {
                manifest.setDicts(mergeList(manifest.getDicts(), dicts));
            }
        }

        // Load commands
        if (resourceDirs.containsKey("commands")) {
            List<CommandDefinitionDTO> commands = loadResourceList(
                    pluginDir.resolve(resourceDirs.get("commands")), CommandDefinitionDTO.class);
            if (!commands.isEmpty()) {
                manifest.setCommands(mergeList(manifest.getCommands(), commands));
            }
        }

        // Load binding rules
        if (resourceDirs.containsKey("bindingRules")) {
            List<BindingRuleDTO> bindingRules = loadResourceList(
                    pluginDir.resolve(resourceDirs.get("bindingRules")), BindingRuleDTO.class);
            if (!bindingRules.isEmpty()) {
                manifest.setBindingRules(mergeList(manifest.getBindingRules(), bindingRules));
            }
        }

        // Load menus
        if (resourceDirs.containsKey("menus")) {
            List<MenuDefinitionDTO> menus = loadResourceList(
                    pluginDir.resolve(resourceDirs.get("menus")), MenuDefinitionDTO.class);
            if (!menus.isEmpty()) {
                manifest.setMenus(mergeList(manifest.getMenus(), menus));
            }
        }

        // Load permissions
        if (resourceDirs.containsKey("permissions")) {
            List<PermissionDefinitionDTO> permissions = loadResourceList(
                    pluginDir.resolve(resourceDirs.get("permissions")), PermissionDefinitionDTO.class);
            if (!permissions.isEmpty()) {
                manifest.setPermissions(mergeList(manifest.getPermissions(), permissions));
            }
        }

        // Load roles
        if (resourceDirs.containsKey("roles")) {
            List<RoleDefinitionDTO> roles = loadResourceList(
                    pluginDir.resolve(resourceDirs.get("roles")), RoleDefinitionDTO.class);
            if (!roles.isEmpty()) {
                manifest.setRoles(mergeList(manifest.getRoles(), roles));
            }
        }

        // Load pages
        if (resourceDirs.containsKey("pages")) {
            List<PageSchemaDTO> pages = loadResourceList(
                    pluginDir.resolve(resourceDirs.get("pages")), PageSchemaDTO.class);
            if (!pages.isEmpty()) {
                List<PageSchemaDTO> convertedPages = pages.stream()
                        .map(this::convertPageDslIfNeeded)
                        .toList();
                manifest.setPages(mergeList(manifest.getPages(), convertedPages));
            }
        }

        // Load processes (if any)
        if (resourceDirs.containsKey("processes")) {
            List<ProcessDefinitionDTO> processes = loadResourceList(
                    pluginDir.resolve(resourceDirs.get("processes")), ProcessDefinitionDTO.class);
            if (!processes.isEmpty()) {
                manifest.setProcesses(mergeList(manifest.getProcesses(), processes));
            }
        }

        // Load i18n resources
        if (resourceDirs.containsKey("i18n")) {
            List<I18nDefinitionDTO> i18n = loadResourceList(
                    pluginDir.resolve(resourceDirs.get("i18n")), I18nDefinitionDTO.class);
            if (!i18n.isEmpty()) {
                manifest.setI18nResources(mergeList(manifest.getI18nResources(), i18n));
            }
        }

        // Load named queries
        if (resourceDirs.containsKey("namedQueries")) {
            List<NamedQueryDefinitionDTO> namedQueries = loadResourceList(
                    pluginDir.resolve(resourceDirs.get("namedQueries")), NamedQueryDefinitionDTO.class);
            if (!namedQueries.isEmpty()) {
                manifest.setNamedQueries(mergeList(manifest.getNamedQueries(), namedQueries));
            }
        }

        // Load saved views
        if (resourceDirs.containsKey("savedViews")) {
            List<SavedViewDefinitionDTO> savedViews = loadResourceList(
                    pluginDir.resolve(resourceDirs.get("savedViews")), SavedViewDefinitionDTO.class);
            if (!savedViews.isEmpty()) {
                manifest.setSavedViews(mergeList(manifest.getSavedViews(), savedViews));
            }
        }
    }

    /**
     * Load resources from either a JSON file (array) or a directory of JSON files.
     */
    private <T> List<T> loadResourceList(Path path, Class<T> clazz) throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }

        if (Files.isRegularFile(path) && path.toString().endsWith(".json")) {
            // Load from single JSON file containing an array
            try {
                JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, clazz);
                return objectMapper.readValue(path.toFile(), listType);
            } catch (IOException e) {
                log.warn("Failed to load resources from file {}: {}", path, e.getMessage());
                return List.of();
            }
        } else if (Files.isDirectory(path)) {
            // Load from directory of JSON files
            return loadResourcesFromDir(path, clazz);
        }

        return List.of();
    }

    /**
     * Load resources from a directory where each file is either a single resource object
     * or an array of resources. Supports both formats for flexibility.
     */
    private <T> List<T> loadResourcesFromDir(Path dir, Class<T> clazz) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> jsonFiles = files
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .toList();
            // Keep deterministic file order while parallelizing per-file parse work.
            List<List<T>> parsedByFile = jsonFiles.parallelStream()
                    .map(file -> parseResourceFile(file, clazz))
                    .toList();

            List<T> resources = new ArrayList<>();
            for (List<T> parsed : parsedByFile) {
                resources.addAll(parsed);
            }
            return resources;
        }
    }

    private <T> List<T> parseResourceFile(Path file, Class<T> clazz) {
        try {
            var node = objectMapper.readTree(file.toFile());
            if (node == null || node.isNull()) {
                return List.of();
            }
            if (node.isArray()) {
                JavaType listType = objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, clazz);
                return objectMapper.convertValue(node, listType);
            }
            return List.of(objectMapper.convertValue(node, clazz));
        } catch (Exception e) {
            log.warn("Failed to load resource from {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    /**
     * Load bindings from directory where each file contains an array of bindings for one model.
     */
    private List<ModelFieldBindingDTO> loadBindingsFromDir(Path dir) throws IOException {
        List<ModelFieldBindingDTO> allBindings = new ArrayList<>();

        try (Stream<Path> files = Files.list(dir)) {
            List<Path> jsonFiles = files
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .toList();

            for (Path file : jsonFiles) {
                try {
                    List<ModelFieldBindingDTO> bindings = objectMapper.readValue(
                            file.toFile(),
                            new TypeReference<List<ModelFieldBindingDTO>>() {});
                    allBindings.addAll(bindings);
                } catch (IOException e) {
                    log.warn("Failed to load bindings from {}: {}", file, e.getMessage());
                }
            }
        }

        return allBindings;
    }

    /**
     * Convert page DSL format to platform PageSchemaDTO format if needed.
     * This handles the conversion from:
     * - pageKey, displayName, modelCode, type, schema -> pageKey, name, modelCode, pageType, dslSchema
     */
    @SuppressWarnings("unchecked")
    private PageSchemaDTO convertPageDslIfNeeded(PageSchemaDTO page) {
        // Check if this is the old format (has displayName instead of name, or type instead of pageType)
        Map<String, Object> rawDsl = page.getDslSchema();

        // If dslSchema is null but we have an old-format page, convert it
        if (rawDsl == null) {
            // The page might be in old format with schema field embedded
            // In this case, the objectMapper already parsed it, but we need to check fields
            return page;
        }

        // Check if pageType is already set or needs conversion from type
        if (page.getPageType() == null || page.getPageType().isBlank()) {
            // Try to infer from dslSchema if it has a "type" field
            Object type = rawDsl.get("type");
            if (type instanceof String typeStr) {
                page.setPageType(typeStr.toLowerCase());
            }
        }

        // Ensure name is set
        if ((page.getName() == null || page.getName().isBlank())
                && (page.getNameZhCN() == null || page.getNameZhCN().isBlank())) {
            // Check if displayName exists in the raw map
            Object displayName = rawDsl.get("displayName");
            if (displayName instanceof String) {
                page.setNameZhCN((String) displayName);
            }
        }

        return page;
    }

    /**
     * Merge two lists, preferring the second list's items for duplicates.
     */
    private <T> List<T> mergeList(List<T> existing, List<T> newItems) {
        if (existing == null || existing.isEmpty()) {
            return new ArrayList<>(newItems);
        }
        List<T> result = new ArrayList<>(existing);
        result.addAll(newItems);
        return result;
    }
}
