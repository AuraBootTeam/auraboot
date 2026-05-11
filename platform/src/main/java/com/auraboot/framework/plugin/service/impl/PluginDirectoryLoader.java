package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.common.util.PathSafetyUtils;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.dto.imports.DashboardDefinitionDTO;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.source.PluginSource;
import com.auraboot.framework.plugin.source.FileSystemPluginSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
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
        try {
            pluginDir = PathSafetyUtils.requireExistingDirectory(pluginDir, "plugin directory");
        } catch (IllegalArgumentException e) {
            throw new PluginException(e.getMessage(), e);
        }
        if (!Files.isDirectory(pluginDir)) {
            throw new PluginException("Plugin path is not a directory: " + pluginDir);
        }

        Path manifestPath = PathSafetyUtils.requireSafeChild(pluginDir, "plugin.json", "plugin manifest path");
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
            loadAgentDefinitionsByConvention(pluginDir, manifest, resourceDirs);

            log.info("Loaded plugin from directory: {} v{}, resources: {}",
                    manifest.getPluginId(), manifest.getVersion(), manifest.getResourceCounts());

            return manifest;

        } catch (IOException e) {
            throw new PluginException("Failed to load plugin from directory: " + e.getMessage(), e);
        }
    }

    /**
     * Load plugin manifest from a PluginSource abstraction.
     * Supports any source type (filesystem, URL, S3, etc.).
     *
     * @param source the plugin source to load from
     * @return extended manifest with all resources loaded
     * @since 7.2.0
     */
    public PluginManifestExtended loadFromSource(PluginSource source) {
        if (!source.isValidPlugin()) {
            throw new PluginException("plugin.json not found in source: " + source.getSourceId());
        }

        try {
            String manifestJson = source.readString("plugin.json");
            PluginManifestExtended manifest = objectMapper.readValue(manifestJson, PluginManifestExtended.class);

            Map<String, String> resourceDirs = manifest.getResourceDirs();
            if (resourceDirs != null && !resourceDirs.isEmpty()) {
                loadResourcesFromSource(source, manifest, resourceDirs);
            }
            loadAgentDefinitionsByConventionFromSource(source, manifest, resourceDirs);

            log.info("Loaded plugin from source: {} v{}, resources: {}",
                    manifest.getPluginId(), manifest.getVersion(), manifest.getResourceCounts());

            return manifest;
        } catch (IOException e) {
            throw new PluginException("Failed to load plugin from source " + source.getSourceId() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Check if a directory contains a valid plugin structure.
     */
    public boolean isValidPluginDirectory(Path pluginDir) {
        try {
            Path manifestPath = PathSafetyUtils.requireSafeChild(pluginDir, "plugin.json", "plugin manifest path");
            return Files.exists(manifestPath);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void loadResourcesFromDirs(Path pluginDir, PluginManifestExtended manifest,
                                       Map<String, String> resourceDirs) throws IOException {
        // Load models
        if (resourceDirs.containsKey("models")) {
            List<ModelDefinitionDTO> models = loadResourceList(
                    resourcePath(pluginDir, resourceDirs, "models"), ModelDefinitionDTO.class);
            if (!models.isEmpty()) {
                manifest.setModels(mergeList(manifest.getModels(), models));
            }
        }

        // Load fields
        if (resourceDirs.containsKey("fields")) {
            List<FieldDefinitionDTO> fields = loadResourceList(
                    resourcePath(pluginDir, resourceDirs, "fields"), FieldDefinitionDTO.class);
            if (!fields.isEmpty()) {
                manifest.setFields(mergeList(manifest.getFields(), fields));
            }
        }

        // Load bindings (modelFieldBindings key)
        String bindingsKey = resourceDirs.containsKey("modelFieldBindings") ? "modelFieldBindings" : "bindings";
        if (resourceDirs.containsKey(bindingsKey)) {
            List<ModelFieldBindingDTO> bindings = loadResourceList(
                    resourcePath(pluginDir, resourceDirs, bindingsKey), ModelFieldBindingDTO.class);
            if (!bindings.isEmpty()) {
                manifest.setModelFieldBindings(mergeList(manifest.getModelFieldBindings(), bindings));
            }
        }

        // Load dicts
        if (resourceDirs.containsKey("dicts")) {
            List<DictDefinitionDTO> dicts = loadResourceList(
                    resourcePath(pluginDir, resourceDirs, "dicts"), DictDefinitionDTO.class);
            if (!dicts.isEmpty()) {
                manifest.setDicts(mergeList(manifest.getDicts(), dicts));
            }
        }

        // Load commands
        if (resourceDirs.containsKey("commands")) {
            List<CommandDefinitionDTO> commands = loadResourceList(
                    resourcePath(pluginDir, resourceDirs, "commands"), CommandDefinitionDTO.class);
            if (!commands.isEmpty()) {
                manifest.setCommands(mergeList(manifest.getCommands(), commands));
            }
        }

        // Load binding rules
        if (resourceDirs.containsKey("bindingRules")) {
            List<BindingRuleDTO> bindingRules = loadResourceList(
                    resourcePath(pluginDir, resourceDirs, "bindingRules"), BindingRuleDTO.class);
            if (!bindingRules.isEmpty()) {
                manifest.setBindingRules(mergeList(manifest.getBindingRules(), bindingRules));
            }
        }

        // Load menus
        if (resourceDirs.containsKey("menus")) {
            List<MenuDefinitionDTO> menus = loadResourceList(
                    resourcePath(pluginDir, resourceDirs, "menus"), MenuDefinitionDTO.class);
            if (!menus.isEmpty()) {
                manifest.setMenus(mergeList(manifest.getMenus(), menus));
            }
        }

        // Load permissions
        if (resourceDirs.containsKey("permissions")) {
            List<PermissionDefinitionDTO> permissions = loadResourceList(
                    resourcePath(pluginDir, resourceDirs, "permissions"), PermissionDefinitionDTO.class);
            if (!permissions.isEmpty()) {
                manifest.setPermissions(mergeList(manifest.getPermissions(), permissions));
            }
        }

        // Load roles
        if (resourceDirs.containsKey("roles")) {
            List<RoleDefinitionDTO> roles = loadResourceList(
                    resourcePath(pluginDir, resourceDirs, "roles"), RoleDefinitionDTO.class);
            if (!roles.isEmpty()) {
                manifest.setRoles(mergeList(manifest.getRoles(), roles));
            }
        }

        // Load pages
        if (resourceDirs.containsKey("pages")) {
            List<PageSchemaDTO> pages = loadResourceList(
                    resourcePath(pluginDir, resourceDirs, "pages"), PageSchemaDTO.class);
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
                    resourcePath(pluginDir, resourceDirs, "processes"), ProcessDefinitionDTO.class);
            if (!processes.isEmpty()) {
                manifest.setProcesses(mergeList(manifest.getProcesses(), processes));
            }
        }

        // Load i18n resources
        if (resourceDirs.containsKey("i18n")) {
            List<I18nDefinitionDTO> i18n = loadResourceList(
                    resourcePath(pluginDir, resourceDirs, "i18n"), I18nDefinitionDTO.class);
            if (!i18n.isEmpty()) {
                manifest.setI18nResources(mergeList(manifest.getI18nResources(), i18n));
            }
        }

        // Load named queries
        if (resourceDirs.containsKey("namedQueries")) {
            List<NamedQueryDefinitionDTO> namedQueries = loadResourceList(
                    resourcePath(pluginDir, resourceDirs, "namedQueries"), NamedQueryDefinitionDTO.class);
            if (!namedQueries.isEmpty()) {
                manifest.setNamedQueries(mergeList(manifest.getNamedQueries(), namedQueries));
            }
        }

        // Load agent definitions
        if (resourceDirs.containsKey("agentDefinitions")) {
            List<AgentDefinitionDTO> agentDefinitions = loadResourceList(
                    resourcePath(pluginDir, resourceDirs, "agentDefinitions"), AgentDefinitionDTO.class);
            if (!agentDefinitions.isEmpty()) {
                manifest.setAgentDefinitions(mergeList(manifest.getAgentDefinitions(), agentDefinitions));
            }
        }

        // Load saved views
        if (resourceDirs.containsKey("savedViews")) {
            List<SavedViewDefinitionDTO> savedViews = loadResourceList(
                    resourcePath(pluginDir, resourceDirs, "savedViews"), SavedViewDefinitionDTO.class);
            if (!savedViews.isEmpty()) {
                manifest.setSavedViews(mergeList(manifest.getSavedViews(), savedViews));
            }
        }

        // Load dashboards (first-class contract: config/dashboards/*.json).
        // Convention over configuration: scan `config/dashboards/` even if the
        // plugin didn't explicitly declare it in resourceDirs.
        String dashboardsPath = resourceDirs.getOrDefault("dashboards", "config/dashboards");
        Path dashboardsDir = PathSafetyUtils.requireSafeChild(pluginDir, dashboardsPath, "dashboards resourceDir");
        if (Files.exists(dashboardsDir) && Files.isDirectory(dashboardsDir)) {
            List<DashboardDefinitionDTO> dashboards = loadResourceList(
                    dashboardsDir, DashboardDefinitionDTO.class);
            if (!dashboards.isEmpty()) {
                manifest.setDashboards(mergeList(manifest.getDashboards(), dashboards));
            }
        }

        // Load rules (Drools)
        if (resourceDirs.containsKey("rules")) {
            List<BpmRuleDefinitionDTO> rules = loadResourceList(
                    resourcePath(pluginDir, resourceDirs, "rules"), BpmRuleDefinitionDTO.class);
            if (!rules.isEmpty()) {
                for (BpmRuleDefinitionDTO rule : rules) {
                    inlineDrlContent(pluginDir, rule);
                }
                manifest.setRules(mergeList(manifest.getRules(), rules));
            }
        }

        // Load SLA configs
        if (resourceDirs.containsKey("sla")) {
            List<SlaConfigDefinitionDTO> slaConfigs = loadResourceList(
                    resourcePath(pluginDir, resourceDirs, "sla"), SlaConfigDefinitionDTO.class);
            if (!slaConfigs.isEmpty()) {
                manifest.setSlaConfigs(mergeList(manifest.getSlaConfigs(), slaConfigs));
            }
        }
    }

    private Path resourcePath(Path pluginDir, Map<String, String> resourceDirs, String key) {
        return PathSafetyUtils.requireSafeChild(pluginDir, resourceDirs.get(key), key + " resourceDir");
    }

    private void loadAgentDefinitionsByConvention(Path pluginDir, PluginManifestExtended manifest,
                                                  Map<String, String> resourceDirs) throws IOException {
        if (manifest.getAgentDefinitions() != null && !manifest.getAgentDefinitions().isEmpty()) {
            return;
        }
        String path = resourceDirs != null
                ? resourceDirs.getOrDefault("agentDefinitions", "config/agent-definitions.json")
                : "config/agent-definitions.json";
        Path agentDefinitionsPath = PathSafetyUtils.requireSafeChild(pluginDir, path, "agentDefinitions resource path");
        if (!Files.exists(agentDefinitionsPath)) {
            return;
        }
        List<AgentDefinitionDTO> agentDefinitions = loadResourceList(agentDefinitionsPath, AgentDefinitionDTO.class);
        if (!agentDefinitions.isEmpty()) {
            manifest.setAgentDefinitions(mergeList(manifest.getAgentDefinitions(), agentDefinitions));
        }
    }

    /**
     * If the rule declares a {@code ruleContentFile} and has no inline
     * {@code ruleContent}, read the DRL file into {@code ruleContent}.
     * Having both is an ambiguous source and rejected.
     */
    private void inlineDrlContent(Path pluginDir, BpmRuleDefinitionDTO rule) {
        String relPath = rule.getRuleContentFile();
        if (relPath == null || relPath.isBlank()) {
            return;
        }
        boolean hasInline = rule.getRuleContent() != null && !rule.getRuleContent().isBlank();
        if (hasInline) {
            throw new PluginException("Rule '" + rule.getRuleCode()
                    + "' declares both ruleContent and ruleContentFile — pick one");
        }
        Path drlPath = PathSafetyUtils.requireSafeChild(pluginDir, relPath, "ruleContentFile");
        if (!Files.exists(drlPath)) {
            throw new PluginException("Rule '" + rule.getRuleCode()
                    + "' ruleContentFile not found: " + relPath);
        }
        try {
            rule.setRuleContent(Files.readString(drlPath));
        } catch (IOException e) {
            throw new PluginException("Failed to read DRL file for rule '" + rule.getRuleCode()
                    + "': " + e.getMessage(), e);
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
     * Normalize page DTO after loading from plugin directory (V2 format only).
     * Ensures name is populated from localized names if missing.
     */
    private PageSchemaDTO convertPageDslIfNeeded(PageSchemaDTO page) {
        // V2 format: kind and blocks are top-level fields on the DTO.
        // No conversion needed — just ensure name is set.
        if ((page.getName() == null || page.getName().isBlank())
                && (page.getNameZhCN() == null || page.getNameZhCN().isBlank())) {
            // Fall back to pageKey as name if nothing else is set
            page.setName(page.getPageKey());
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

    // ==================== PluginSource-based loading ====================

    private void loadResourcesFromSource(PluginSource source, PluginManifestExtended manifest,
                                          Map<String, String> resourceDirs) throws IOException {
        loadSourceResource(source, resourceDirs, "models", ModelDefinitionDTO.class,
                manifest::getModels, manifest::setModels);
        loadSourceResource(source, resourceDirs, "fields", FieldDefinitionDTO.class,
                manifest::getFields, manifest::setFields);

        String bindingsKey = resourceDirs.containsKey("modelFieldBindings") ? "modelFieldBindings" : "bindings";
        loadSourceResource(source, resourceDirs, bindingsKey, ModelFieldBindingDTO.class,
                manifest::getModelFieldBindings, manifest::setModelFieldBindings);

        loadSourceResource(source, resourceDirs, "dicts", DictDefinitionDTO.class,
                manifest::getDicts, manifest::setDicts);
        loadSourceResource(source, resourceDirs, "commands", CommandDefinitionDTO.class,
                manifest::getCommands, manifest::setCommands);
        loadSourceResource(source, resourceDirs, "bindingRules", BindingRuleDTO.class,
                manifest::getBindingRules, manifest::setBindingRules);
        loadSourceResource(source, resourceDirs, "menus", MenuDefinitionDTO.class,
                manifest::getMenus, manifest::setMenus);
        loadSourceResource(source, resourceDirs, "permissions", PermissionDefinitionDTO.class,
                manifest::getPermissions, manifest::setPermissions);
        loadSourceResource(source, resourceDirs, "roles", RoleDefinitionDTO.class,
                manifest::getRoles, manifest::setRoles);

        // Pages need conversion
        if (resourceDirs.containsKey("pages")) {
            List<PageSchemaDTO> pages = loadResourceListFromSource(source, resourceDirs.get("pages"), PageSchemaDTO.class);
            if (!pages.isEmpty()) {
                List<PageSchemaDTO> converted = pages.stream().map(this::convertPageDslIfNeeded).toList();
                manifest.setPages(mergeList(manifest.getPages(), converted));
            }
        }

        loadSourceResource(source, resourceDirs, "processes", ProcessDefinitionDTO.class,
                manifest::getProcesses, manifest::setProcesses);
        loadSourceResource(source, resourceDirs, "i18n", I18nDefinitionDTO.class,
                manifest::getI18nResources, manifest::setI18nResources);
        loadSourceResource(source, resourceDirs, "namedQueries", NamedQueryDefinitionDTO.class,
                manifest::getNamedQueries, manifest::setNamedQueries);
        loadSourceResource(source, resourceDirs, "agentDefinitions", AgentDefinitionDTO.class,
                manifest::getAgentDefinitions, manifest::setAgentDefinitions);
        loadSourceResource(source, resourceDirs, "savedViews", SavedViewDefinitionDTO.class,
                manifest::getSavedViews, manifest::setSavedViews);
        loadSourceResource(source, resourceDirs, "dashboards", DashboardDefinitionDTO.class,
                manifest::getDashboards, manifest::setDashboards);

        // Rules (DRL-on-disk inlining via ruleContentFile)
        if (resourceDirs.containsKey("rules")) {
            List<BpmRuleDefinitionDTO> rules = loadResourceListFromSource(
                    source, resourceDirs.get("rules"), BpmRuleDefinitionDTO.class);
            if (!rules.isEmpty()) {
                for (BpmRuleDefinitionDTO rule : rules) {
                    inlineDrlContentFromSource(source, rule);
                }
                manifest.setRules(mergeList(manifest.getRules(), rules));
            }
        }

        // SLA configs
        loadSourceResource(source, resourceDirs, "sla", SlaConfigDefinitionDTO.class,
                manifest::getSlaConfigs, manifest::setSlaConfigs);
    }

    private void loadAgentDefinitionsByConventionFromSource(PluginSource source, PluginManifestExtended manifest,
                                                            Map<String, String> resourceDirs) throws IOException {
        if (manifest.getAgentDefinitions() != null && !manifest.getAgentDefinitions().isEmpty()) {
            return;
        }
        String path = resourceDirs != null
                ? resourceDirs.getOrDefault("agentDefinitions", "config/agent-definitions.json")
                : "config/agent-definitions.json";
        if (!source.exists(path)) {
            return;
        }
        String json = source.readString(path);
        JavaType listType = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, AgentDefinitionDTO.class);
        List<AgentDefinitionDTO> agentDefinitions = objectMapper.readValue(json, listType);
        if (!agentDefinitions.isEmpty()) {
            manifest.setAgentDefinitions(mergeList(manifest.getAgentDefinitions(), agentDefinitions));
        }
    }

    private void inlineDrlContentFromSource(PluginSource source, BpmRuleDefinitionDTO rule) {
        String relPath = rule.getRuleContentFile();
        if (relPath == null || relPath.isBlank()) {
            return;
        }
        boolean hasInline = rule.getRuleContent() != null && !rule.getRuleContent().isBlank();
        if (hasInline) {
            throw new PluginException("Rule '" + rule.getRuleCode()
                    + "' declares both ruleContent and ruleContentFile — pick one");
        }
        if (!source.exists(relPath)) {
            throw new PluginException("Rule '" + rule.getRuleCode()
                    + "' ruleContentFile not found: " + relPath);
        }
        try {
            rule.setRuleContent(source.readString(relPath));
        } catch (IOException e) {
            throw new PluginException("Failed to read DRL file for rule '" + rule.getRuleCode()
                    + "': " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface ListGetter<T> { List<T> get(); }

    @FunctionalInterface
    private interface ListSetter<T> { void set(List<T> items); }

    private <T> void loadSourceResource(PluginSource source, Map<String, String> resourceDirs,
                                         String key, Class<T> clazz,
                                         ListGetter<T> getter, ListSetter<T> setter) throws IOException {
        if (!resourceDirs.containsKey(key)) return;
        List<T> items = loadResourceListFromSource(source, resourceDirs.get(key), clazz);
        if (!items.isEmpty()) {
            setter.set(mergeList(getter.get(), items));
        }
    }

    private <T> List<T> loadResourceListFromSource(PluginSource source, String relativeDir, Class<T> clazz) throws IOException {
        // Check if it's a single file (e.g., "models.json") or a directory
        String jsonPath = relativeDir.endsWith(".json") ? relativeDir : relativeDir + ".json";
        if (source.exists(jsonPath) && !relativeDir.endsWith(".json")) {
            // Try as single JSON array file
            String json = source.readString(jsonPath);
            JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, clazz);
            return objectMapper.readValue(json, listType);
        }

        if (source.exists(relativeDir + ".json") && relativeDir.endsWith(".json")) {
            String json = source.readString(relativeDir);
            JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, clazz);
            return objectMapper.readValue(json, listType);
        }

        // Load from directory of individual JSON files
        List<String> files = source.listFiles(relativeDir, ".json");
        if (files.isEmpty()) return List.of();

        List<T> result = new ArrayList<>();
        for (String filePath : files) {
            try {
                String json = source.readString(filePath);
                var node = objectMapper.readTree(json);
                if (node == null || node.isNull()) continue;
                if (node.isArray()) {
                    JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, clazz);
                    result.addAll(objectMapper.convertValue(node, listType));
                } else {
                    result.add(objectMapper.convertValue(node, clazz));
                }
            } catch (Exception e) {
                log.warn("Failed to load resource from source {}/{}: {}", source.getSourceId(), filePath, e.getMessage());
            }
        }
        return result;
    }
}
