package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.exception.PluginException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.common.util.LogSanitizer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Reads declared plugin resources from an in-memory ZIP file map. */
@Slf4j
@RequiredArgsConstructor
final class PluginZipResourceReader {
    private final ObjectMapper objectMapper;

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }

    /**
     * Load resources from ZIP files based on resourceDirs configuration.
     * This mirrors PluginDirectoryLoader.loadResourcesFromDirs but works with in-memory byte arrays.
     */
    void loadResourcesFromZipFiles(PluginManifestExtended manifest, Map<String, byte[]> files) {
        Map<String, String> resourceDirs = manifest.getResourceDirs();
        if (resourceDirs == null || resourceDirs.isEmpty()) {
            loadAgentDefinitionsFromZipByConvention(manifest, files, resourceDirs);
            return;
        }

        try {
            // Load models
            if (resourceDirs.containsKey("models")) {
                List<ModelDefinitionDTO> models = loadResourceListFromZip(files, resourceDirs.get("models"), ModelDefinitionDTO.class);
                if (!models.isEmpty()) {
                    manifest.setModels(mergeResourceList(manifest.getModels(), models));
                }
            }

            // Load fields
            if (resourceDirs.containsKey("fields")) {
                List<FieldDefinitionDTO> fields = loadResourceListFromZip(files, resourceDirs.get("fields"), FieldDefinitionDTO.class);
                if (!fields.isEmpty()) {
                    manifest.setFields(mergeResourceList(manifest.getFields(), fields));
                }
            }

            // Load bindings (modelFieldBindings key)
            String bindingsKey = resourceDirs.containsKey("modelFieldBindings") ? "modelFieldBindings" : "bindings";
            if (resourceDirs.containsKey(bindingsKey)) {
                List<ModelFieldBindingDTO> bindings = loadResourceListFromZip(files, resourceDirs.get(bindingsKey), ModelFieldBindingDTO.class);
                if (!bindings.isEmpty()) {
                    manifest.setModelFieldBindings(mergeResourceList(manifest.getModelFieldBindings(), bindings));
                }
            }

            // Load dicts
            if (resourceDirs.containsKey("dicts")) {
                List<DictDefinitionDTO> dicts = loadResourceListFromZip(files, resourceDirs.get("dicts"), DictDefinitionDTO.class);
                if (!dicts.isEmpty()) {
                    manifest.setDicts(mergeResourceList(manifest.getDicts(), dicts));
                }
            }

            // Load commands
            if (resourceDirs.containsKey("commands")) {
                List<CommandDefinitionDTO> commands = loadResourceListFromZip(files, resourceDirs.get("commands"), CommandDefinitionDTO.class);
                if (!commands.isEmpty()) {
                    manifest.setCommands(mergeResourceList(manifest.getCommands(), commands));
                }
            }

            // Load binding rules
            if (resourceDirs.containsKey("bindingRules")) {
                List<BindingRuleDTO> bindingRules = loadRequiredResourceListFromZip(
                        files, resourceDirs.get("bindingRules"), BindingRuleDTO.class);
                if (!bindingRules.isEmpty()) {
                    manifest.setBindingRules(mergeResourceList(manifest.getBindingRules(), bindingRules));
                }
            }

            // Load menus
            if (resourceDirs.containsKey("menus")) {
                List<MenuDefinitionDTO> menus = loadResourceListFromZip(files, resourceDirs.get("menus"), MenuDefinitionDTO.class);
                if (!menus.isEmpty()) {
                    manifest.setMenus(mergeResourceList(manifest.getMenus(), menus));
                    log.debug("Loaded {} menus from ZIP: {}", menus.size(), logSafe(resourceDirs.get("menus")));
                }
            }

            // Load permissions
            if (resourceDirs.containsKey("permissions")) {
                List<PermissionDefinitionDTO> permissions = loadResourceListFromZip(files, resourceDirs.get("permissions"), PermissionDefinitionDTO.class);
                if (!permissions.isEmpty()) {
                    manifest.setPermissions(mergeResourceList(manifest.getPermissions(), permissions));
                }
            }

            // Load roles
            if (resourceDirs.containsKey("roles")) {
                List<RoleDefinitionDTO> roles = loadResourceListFromZip(files, resourceDirs.get("roles"), RoleDefinitionDTO.class);
                if (!roles.isEmpty()) {
                    manifest.setRoles(mergeResourceList(manifest.getRoles(), roles));
                }
            }

            // Load field masks
            if (resourceDirs.containsKey("fieldMasks")) {
                List<FieldMaskDefinitionDTO> fieldMasks = loadResourceListFromZip(files, resourceDirs.get("fieldMasks"), FieldMaskDefinitionDTO.class);
                if (!fieldMasks.isEmpty()) {
                    manifest.setFieldMasks(mergeResourceList(manifest.getFieldMasks(), fieldMasks));
                }
            }

            // Load capabilities
            if (resourceDirs.containsKey("capabilities")) {
                List<CapabilityDefinitionDTO> capabilities = loadResourceListFromZip(files, resourceDirs.get("capabilities"), CapabilityDefinitionDTO.class);
                if (!capabilities.isEmpty()) {
                    manifest.setCapabilities(mergeResourceList(manifest.getCapabilities(), capabilities));
                }
            }

            // Load pages
            if (resourceDirs.containsKey("pages")) {
                List<PageSchemaDTO> pages = loadResourceListFromZip(files, resourceDirs.get("pages"), PageSchemaDTO.class);
                if (!pages.isEmpty()) {
                    manifest.setPages(mergeResourceList(manifest.getPages(), pages));
                }
            }

            // Load named queries
            if (resourceDirs.containsKey("pageContributions")) {
                List<PageContributionDefinitionDTO> contributions = loadRequiredResourceListFromZip(
                        files, resourceDirs.get("pageContributions"), PageContributionDefinitionDTO.class);
                if (!contributions.isEmpty()) {
                    manifest.setPageContributions(mergeResourceList(manifest.getPageContributions(), contributions));
                }
            }

            if (resourceDirs.containsKey("namedQueries")) {
                List<NamedQueryDefinitionDTO> namedQueries = loadResourceListFromZip(
                        files, resourceDirs.get("namedQueries"), NamedQueryDefinitionDTO.class);
                if (!namedQueries.isEmpty()) {
                    manifest.setNamedQueries(mergeResourceList(manifest.getNamedQueries(), namedQueries));
                }
            }

            // Load agent definitions
            if (resourceDirs.containsKey("agentDefinitions")) {
                List<AgentDefinitionDTO> agentDefinitions = loadResourceListFromZip(
                        files, resourceDirs.get("agentDefinitions"), AgentDefinitionDTO.class);
                if (!agentDefinitions.isEmpty()) {
                    manifest.setAgentDefinitions(mergeResourceList(manifest.getAgentDefinitions(), agentDefinitions));
                }
            }

            // Load saved views
            if (resourceDirs.containsKey("savedViews")) {
                List<SavedViewDefinitionDTO> savedViews = loadResourceListFromZip(
                        files, resourceDirs.get("savedViews"), SavedViewDefinitionDTO.class);
                if (!savedViews.isEmpty()) {
                    manifest.setSavedViews(mergeResourceList(manifest.getSavedViews(), savedViews));
                }
            }

            // Load notification templates
            if (resourceDirs.containsKey("notificationTemplates")) {
                List<NotificationTemplateDefinitionDTO> templates = loadResourceListFromZip(
                        files, resourceDirs.get("notificationTemplates"), NotificationTemplateDefinitionDTO.class);
                if (!templates.isEmpty()) {
                    manifest.setNotificationTemplates(
                            mergeResourceList(manifest.getNotificationTemplates(), templates));
                }
            }

            // Load Decision Runtime definitions
            if (resourceDirs.containsKey("decisionDefinitions")) {
                List<DecisionDefinitionSeedDTO> decisions = loadResourceListFromZip(
                        files, resourceDirs.get("decisionDefinitions"), DecisionDefinitionSeedDTO.class);
                if (!decisions.isEmpty()) {
                    manifest.setDecisionDefinitions(
                            mergeResourceList(manifest.getDecisionDefinitions(), decisions));
                }
            }

            // Load reusable condition fragments
            if (resourceDirs.containsKey("conditionFragments")) {
                List<ConditionFragmentSeedDTO> fragments = loadResourceListFromZip(
                        files, resourceDirs.get("conditionFragments"), ConditionFragmentSeedDTO.class);
                if (!fragments.isEmpty()) {
                    manifest.setConditionFragments(
                            mergeResourceList(manifest.getConditionFragments(), fragments));
                }
            }

            // Load EventPolicy seeds
            if (resourceDirs.containsKey("eventPolicies")) {
                List<EventPolicySeedDTO> policies = loadResourceListFromZip(
                        files, resourceDirs.get("eventPolicies"), EventPolicySeedDTO.class);
                if (!policies.isEmpty()) {
                    manifest.setEventPolicies(
                            mergeResourceList(manifest.getEventPolicies(), policies));
                }
            }

            // Load Automation seeds
            if (resourceDirs.containsKey("automations")) {
                List<AutomationDefinitionDTO> automations = loadResourceListFromZip(
                        files, resourceDirs.get("automations"), AutomationDefinitionDTO.class);
                if (!automations.isEmpty()) {
                    manifest.setAutomations(
                            mergeResourceList(manifest.getAutomations(), automations));
                }
            }

            // Load dashboards (first-class contract: config/dashboards/*.json)
            if (resourceDirs.containsKey("dashboards")) {
                List<com.auraboot.framework.plugin.dto.imports.DashboardDefinitionDTO> dashboards =
                        loadResourceListFromZip(files, resourceDirs.get("dashboards"),
                                com.auraboot.framework.plugin.dto.imports.DashboardDefinitionDTO.class);
                if (!dashboards.isEmpty()) {
                    manifest.setDashboards(mergeResourceList(manifest.getDashboards(), dashboards));
                }
            }

            if (resourceDirs.containsKey("semantic")) {
                List<PluginManifestExtended.SemanticResource> semanticResources =
                        loadSemanticResourcesFromZip(files, resourceDirs.get("semantic"));
                if (!semanticResources.isEmpty()) {
                    manifest.setSemanticResources(
                            mergeResourceList(manifest.getSemanticResources(), semanticResources));
                }
            }

            loadAgentDefinitionsFromZipByConvention(manifest, files, resourceDirs);

            log.info("Loaded resources from ZIP resourceDirs: {}", logSafe(manifest.getResourceCounts()));

        } catch (PluginException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to load some resources from ZIP: {}", logSafe(e.getMessage()));
        }
    }

    private void loadAgentDefinitionsFromZipByConvention(PluginManifestExtended manifest, Map<String, byte[]> files,
                                                         Map<String, String> resourceDirs) {
        if (manifest.getAgentDefinitions() != null && !manifest.getAgentDefinitions().isEmpty()) {
            return;
        }
        String path = resourceDirs != null
                ? resourceDirs.getOrDefault("agentDefinitions", "config/agent-definitions.json")
                : "config/agent-definitions.json";
        List<AgentDefinitionDTO> agentDefinitions = loadResourceListFromZip(files, path, AgentDefinitionDTO.class);
        if (!agentDefinitions.isEmpty()) {
            manifest.setAgentDefinitions(mergeResourceList(manifest.getAgentDefinitions(), agentDefinitions));
        }
    }

    /**
     * Load a list of resources from ZIP files map.
     */
    private <T> List<T> loadResourceListFromZip(Map<String, byte[]> files, String path, Class<T> clazz) {
        byte[] content = files.get(path);
        if (content != null) {
            try {
                com.fasterxml.jackson.databind.JavaType listType = objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, clazz);
                return objectMapper.readValue(new String(content, StandardCharsets.UTF_8), listType);
            } catch (Exception e) {
                // Commands are fail-closed via PluginResourceParsePolicy: a dropped command file must
                // fail the import instead of disappearing behind a green result.
                return PluginResourceParsePolicy.onResourceParseFailure(path, clazz, e);
            }
        }

        // Directory layout: aggregate every <path>/*.json entry. Each file may be either
        // a single resource object OR an array of resources (mirrors PluginDirectoryLoader.parseResourceFile).
        String prefix = path.endsWith("/") ? path : path + "/";
        List<String> children = files.keySet().stream()
                .filter(k -> k.startsWith(prefix) && k.endsWith(".json")
                        && k.indexOf('/', prefix.length()) < 0)
                .sorted()
                .toList();
        if (children.isEmpty()) {
            log.debug("Resource path not found in ZIP (file or directory): {}", logSafe(path));
            return List.of();
        }

        com.fasterxml.jackson.databind.JavaType listType = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, clazz);
        List<T> resources = new ArrayList<>();
        for (String child : children) {
            try {
                var node = objectMapper.readTree(new String(files.get(child), StandardCharsets.UTF_8));
                if (node == null || node.isNull()) continue;
                if (node.isArray()) {
                    resources.addAll(objectMapper.convertValue(node, listType));
                } else {
                    resources.add(objectMapper.convertValue(node, clazz));
                }
            } catch (Exception e) {
                resources.addAll(PluginResourceParsePolicy.onResourceParseFailure(child, clazz, e));
            }
        }
        return resources;
    }

    private List<PluginManifestExtended.SemanticResource> loadSemanticResourcesFromZip(
            Map<String, byte[]> files, String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("/") || Arrays.asList(normalized.split("/")).contains("..")) {
            throw new PluginException("Invalid semantic resource path: " + path);
        }

        List<String> resourcePaths;
        if (normalized.endsWith(".semantic.yml")) {
            resourcePaths = files.containsKey(normalized) ? List.of(normalized) : List.of();
        } else {
            String prefix = normalized.endsWith("/") ? normalized : normalized + "/";
            resourcePaths = files.keySet().stream()
                    .filter(entry -> entry.startsWith(prefix)
                            && entry.endsWith(".semantic.yml")
                            && entry.indexOf('/', prefix.length()) < 0)
                    .sorted()
                    .toList();
        }

        List<PluginManifestExtended.SemanticResource> resources =
                new ArrayList<>(resourcePaths.size());
        for (String resourcePath : resourcePaths) {
            resources.add(new PluginManifestExtended.SemanticResource(
                    resourcePath, files.get(resourcePath)));
        }
        return resources;
    }

    /**
     * Strict ZIP resource loader for first-class declared resourceDirs where
     * silently dropping an invalid file would create a broken plugin import.
     */
    private <T> List<T> loadRequiredResourceListFromZip(Map<String, byte[]> files, String path, Class<T> clazz) {
        byte[] content = files.get(path);
        if (content != null) {
            return parseZipResourceFile(path, content, clazz);
        }

        String prefix = path.endsWith("/") ? path : path + "/";
        List<String> children = files.keySet().stream()
                .filter(k -> k.startsWith(prefix) && k.endsWith(".json")
                        && k.indexOf('/', prefix.length()) < 0)
                .sorted()
                .toList();
        if (children.isEmpty()) {
            throw new PluginException("Declared ZIP resource path not found: " + path);
        }

        List<T> resources = new ArrayList<>();
        for (String child : children) {
            resources.addAll(parseZipResourceFile(child, files.get(child), clazz));
        }
        return resources;
    }

    private <T> List<T> parseZipResourceFile(String entryName, byte[] content, Class<T> clazz) {
        try {
            com.fasterxml.jackson.databind.JavaType listType = objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, clazz);
            var node = objectMapper.readTree(new String(content, StandardCharsets.UTF_8));
            if (node == null || node.isNull()) {
                return List.of();
            }
            if (node.isArray()) {
                return objectMapper.convertValue(node, listType);
            }
            return List.of(objectMapper.convertValue(node, clazz));
        } catch (Exception e) {
            PluginException exception = new PluginException(
                    "Failed to parse ZIP resource file " + entryName + ": " + e.getMessage());
            exception.initCause(e);
            throw exception;
        }
    }

    /**
     * Merge two resource lists.
     */
    private <T> List<T> mergeResourceList(List<T> existing, List<T> newItems) {
        if (existing == null || existing.isEmpty()) {
            return new ArrayList<>(newItems);
        }
        List<T> result = new ArrayList<>(existing);
        result.addAll(newItems);
        return result;
    }

}
