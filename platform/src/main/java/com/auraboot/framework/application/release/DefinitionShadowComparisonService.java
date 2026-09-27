package com.auraboot.framework.application.release;

import com.auraboot.framework.plugin.entity.PluginResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Read-only comparison between a published stable release and legacy tenant import snapshots. */
@Slf4j
@Service
public final class DefinitionShadowComparisonService {
    public enum Classification { EXACT_MATCH, DRIFTED, VERSION_MISMATCH, MISSING }
    public record Difference(String componentKey, String resourceType, String resourceCode,
                             String reason, String expectedDigest, String actualDigest) {}
    public record Report(long tenantId, String applicationCode, String releaseId, String releaseDigest,
                         Classification classification, int comparedResources, List<Difference> differences) {}

    private static final List<ApplicationDefinitionResolver.ResourceType> TYPES = List.of(
            ApplicationDefinitionResolver.ResourceType.MODEL,
            ApplicationDefinitionResolver.ResourceType.FIELD,
            ApplicationDefinitionResolver.ResourceType.COMMAND,
            ApplicationDefinitionResolver.ResourceType.PERMISSION,
            ApplicationDefinitionResolver.ResourceType.MENU,
            ApplicationDefinitionResolver.ResourceType.PAGE);

    private final ApplicationDefinitionMapper definitions;
    private final ApplicationDefinitionResolver resolver;
    private final ObjectMapper mapper;

    public DefinitionShadowComparisonService(ApplicationDefinitionMapper definitions,
                                             ApplicationDefinitionResolver resolver, ObjectMapper mapper) {
        this.definitions = definitions;
        this.resolver = resolver;
        this.mapper = mapper;
    }

    public Report comparePublishedStable(long tenantId, String applicationCode) {
        require(tenantId > 0, "Positive tenant ID required");
        var release = resolver.publishedStableRelease(applicationCode);
        var differences = new ArrayList<Difference>();
        int compared = 0;
        for (var component : release.components()) {
            var plugin = definitions.findTenantPlugin(tenantId, component.manifest().getPluginId());
            if (plugin == null) {
                differences.add(new Difference(component.componentKey(), "plugin", component.manifest().getPluginId(),
                        "plugin-missing", null, null));
                continue;
            }
            if (!Objects.equals(component.manifest().getVersion(), plugin.getVersion())) {
                differences.add(new Difference(component.componentKey(), "plugin", component.manifest().getPluginId(),
                        "plugin-version-mismatch", digestText(component.manifest().getVersion()), digestText(plugin.getVersion())));
                continue;
            }
            Map<String, PluginResource> actual = new HashMap<>();
            List<PluginResource> rows = definitions.findComparableResources(tenantId, plugin.getPid());
            if (rows != null) {
                for (PluginResource row : rows) {
                    String identity = row.getResourceType() + ":" + row.getResourceCode();
                    require(actual.putIfAbsent(identity, row) == null, "Duplicate legacy plugin resource identity");
                }
            }
            Set<String> expectedIdentities = new java.util.HashSet<>();
            for (var type : TYPES) {
                for (var entry : resolver.resources(component.manifest(), type).entrySet()) {
                    compared++;
                    String identity = type.name().toLowerCase() + ":" + entry.getKey();
                    expectedIdentities.add(identity);
                    PluginResource legacy = actual.get(identity);
                    String expectedDigest = digest(entry.getValue());
                    if (legacy == null || legacy.getImportSnapshot() == null) {
                        differences.add(new Difference(component.componentKey(), type.name().toLowerCase(), entry.getKey(),
                                "resource-missing", expectedDigest, null));
                        continue;
                    }
                    String actualDigest = digest(mapper.valueToTree(legacy.getImportSnapshot()));
                    if (Boolean.TRUE.equals(legacy.getUserModified()) || !expectedDigest.equals(actualDigest)) {
                        differences.add(new Difference(component.componentKey(), type.name().toLowerCase(), entry.getKey(),
                                Boolean.TRUE.equals(legacy.getUserModified()) ? "tenant-modified" : "definition-digest-mismatch",
                                expectedDigest, actualDigest));
                    }
                }
            }
            actual.entrySet().stream().filter(entry -> !expectedIdentities.contains(entry.getKey())).forEach(entry -> {
                PluginResource legacy = entry.getValue();
                differences.add(new Difference(component.componentKey(), legacy.getResourceType(), legacy.getResourceCode(),
                        "legacy-resource-not-in-release", null,
                        legacy.getImportSnapshot() == null ? null : digest(mapper.valueToTree(legacy.getImportSnapshot()))));
            });
        }
        Classification classification = classify(differences);
        differences.sort(Comparator.comparing(Difference::componentKey)
                .thenComparing(Difference::resourceType).thenComparing(Difference::resourceCode));
        Report report = new Report(tenantId, applicationCode, release.release().releaseId(), release.release().releaseDigest(),
                classification, compared, List.copyOf(differences));
        log.info("Application definition shadow comparison completed: tenantId={}, applicationCode={}, releaseId={}, classification={}, comparedResources={}, differenceCount={}",
                tenantId, applicationCode, report.releaseId(), classification, compared, differences.size());
        return report;
    }

    private static Classification classify(List<Difference> differences) {
        if (differences.stream().anyMatch(item -> item.reason().equals("plugin-version-mismatch"))) {
            return Classification.VERSION_MISMATCH;
        }
        if (differences.stream().anyMatch(item -> item.reason().endsWith("missing"))) {
            return Classification.MISSING;
        }
        return differences.isEmpty() ? Classification.EXACT_MATCH : Classification.DRIFTED;
    }

    private String digest(JsonNode value) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(canonical(value));
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException | java.io.IOException failure) {
            throw new IllegalStateException("Cannot canonicalize definition", failure);
        }
    }

    private String digestText(String value) {
        return digest(mapper.valueToTree(value));
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode object = mapper.createObjectNode();
            var fields = new TreeMap<String, JsonNode>();
            value.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, child) -> object.set(key, canonical(child)));
            return object;
        }
        if (value.isArray()) {
            ArrayNode array = mapper.createArrayNode();
            value.forEach(child -> array.add(canonical(child)));
            return array;
        }
        return value;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }
}
