package com.auraboot.framework.application.release;

import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Resolves immutable release definitions without bypassing tenant filtering or copying shared rows. */
@Service
public final class ApplicationDefinitionResolver {
    public enum ResourceType { MODEL, FIELD, COMMAND, PERMISSION, MENU, PAGE }

    public record ReleaseSelection(long applicationId, String applicationCode, String releaseId,
                                   String releaseDigest, String sourceLockIdentity,
                                   String bindingStatus, long bindingVersion) {}
    public record ComponentDefinitions(String componentKey, String componentVersion,
                                       String componentDigest, PluginManifestExtended manifest) {}
    public record ReleaseDefinitions(ReleaseSelection release, List<ComponentDefinitions> components) {}
    public record ResolvedDefinition(ReleaseSelection release, String componentKey, String componentDigest,
                                     ResourceType resourceType, String namespace, String code,
                                     Long environmentId, JsonNode definition) {}
    public record UniqueDefinitionLookup(ReleaseDefinitions release, ResolvedDefinition definition) {
        public boolean containsPlugin(String pluginId) {
            return pluginId != null && release.components().stream()
                    .anyMatch(component -> pluginId.equals(component.manifest().getPluginId()));
        }
    }

    private final ApplicationDefinitionMapper definitions;
    private final ApplicationDefinitionBundle bundle;
    private final ObjectMapper mapper;

    public ApplicationDefinitionResolver(ApplicationDefinitionMapper definitions,
                                         ApplicationDefinitionBundle bundle, ObjectMapper mapper) {
        this.definitions = definitions;
        this.bundle = bundle;
        this.mapper = mapper;
    }

    public ResolvedDefinition resolve(long tenantId, String applicationCode, ResourceType type,
                                      String namespace, String code, Long environmentId) {
        require(tenantId > 0, "Positive tenant ID required");
        if (environmentId != null) require(environmentId > 0, "Positive environment ID required");
        require(type != null && namespace != null && !namespace.isBlank() && code != null && !code.isBlank(),
                "Resource type, namespace, and code required");
        ReleaseDefinitions release = boundRelease(tenantId, applicationCode);
        var matches = new ArrayList<ResolvedDefinition>();
        for (ComponentDefinitions component : release.components()) {
            if (!namespace.equals(component.manifest().getNamespace())) continue;
            JsonNode definition = resources(component.manifest(), type).get(code);
            if (definition != null) matches.add(new ResolvedDefinition(release.release(), component.componentKey(),
                    component.componentDigest(), type, namespace, code, environmentId, definition));
        }
        require(matches.size() == 1, matches.isEmpty()
                ? "Definition not found in the bound application release"
                : "Definition key is ambiguous in the bound application release");
        return matches.getFirst();
    }

    /** Resolves a globally unique resource key from the tenant's exact bound release. */
    public ResolvedDefinition findUnique(long tenantId, String applicationCode, ResourceType type,
                                         String code, Long environmentId) {
        return lookupUnique(tenantId, applicationCode, type, code, environmentId).definition();
    }

    /** Resolves a unique key and retains the exact Release used to decide absence and ownership. */
    public UniqueDefinitionLookup lookupUnique(long tenantId, String applicationCode, ResourceType type,
                                               String code, Long environmentId) {
        require(tenantId > 0, "Positive tenant ID required");
        if (environmentId != null) require(environmentId > 0, "Positive environment ID required");
        require(type != null && code != null && !code.isBlank(), "Resource type and code required");
        ReleaseDefinitions release = boundRelease(tenantId, applicationCode);
        var matches = new ArrayList<ResolvedDefinition>();
        for (ComponentDefinitions component : release.components()) {
            JsonNode definition = resources(component.manifest(), type).get(code);
            if (definition != null) matches.add(new ResolvedDefinition(release.release(), component.componentKey(),
                    component.componentDigest(), type, component.manifest().getNamespace(), code,
                    environmentId, definition));
        }
        require(matches.size() <= 1, "Definition key is ambiguous in the bound application release");
        return new UniqueDefinitionLookup(release, matches.isEmpty() ? null : matches.getFirst());
    }

    public ReleaseDefinitions boundRelease(long tenantId, String applicationCode) {
        return load(requireRelease(definitions.findBoundRelease(tenantId, applicationCode),
                "Tenant application binding is required"));
    }

    public ReleaseDefinitions publishedStableRelease(String applicationCode) {
        return load(requireRelease(definitions.findPublishedStableRelease(applicationCode),
                "Published stable application release is required"));
    }

    Map<String, JsonNode> resources(PluginManifestExtended manifest, ResourceType type) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        switch (type) {
            case MODEL -> add(result, manifest.getModels(), item -> item.getCode());
            case FIELD -> add(result, manifest.getFields(), item -> item.getCode());
            case COMMAND -> add(result, manifest.getCommands(), item -> item.getCode());
            case PERMISSION -> add(result, manifest.getPermissions(), item -> item.getCode());
            case MENU -> add(result, manifest.getMenus(), item -> item.getCode());
            case PAGE -> add(result, manifest.getPages(), item -> item.getPageKey());
        }
        return Map.copyOf(result);
    }

    private <T> void add(Map<String, JsonNode> target, List<T> items, Function<T, String> key) {
        if (items == null) return;
        for (T item : items) {
            String value = key.apply(item);
            require(value != null && !value.isBlank() && target.putIfAbsent(value, mapper.valueToTree(item)) == null,
                    "Definition resource key is blank or duplicated");
        }
    }

    private ReleaseDefinitions load(ApplicationDefinitionMapper.ReleaseRow row) {
        List<ApplicationDefinitionMapper.ComponentRow> components = definitions.findDefinitionComponents(row.releaseId);
        require(components != null && !components.isEmpty(), "Application release has no definition components");
        var release = new ReleaseSelection(row.applicationId, row.code, row.releaseId, row.releaseDigest,
                row.sourceLockIdentity, row.status, row.bindingVersion);
        var loaded = components.stream().map(component -> new ComponentDefinitions(component.componentKey,
                component.componentVersion, component.componentDigest,
                bundle.load(row.code, row.sourceLockIdentity, component))).toList();
        return new ReleaseDefinitions(release, loaded);
    }

    private static ApplicationDefinitionMapper.ReleaseRow requireRelease(
            ApplicationDefinitionMapper.ReleaseRow row, String message) {
        require(row != null && row.releaseId != null && row.releaseId.matches("[0-9A-HJKMNP-TV-Z]{26}")
                        && row.releaseDigest != null && row.releaseDigest.matches("sha256:[0-9a-f]{64}")
                        && row.sourceLockIdentity != null && row.sourceLockIdentity.matches("sha256:[0-9a-f]{64}"),
                message);
        return row;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
