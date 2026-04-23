package com.auraboot.framework.plugin.template;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Registry of application templates discovered from plugin directories.
 *
 * <p>Templates are identified primarily by {@code plugin.json} metadata
 * ({@code catalogType=template}) and continue to support the legacy
 * {@code plugins/templates/*} directory during the migration period.</p>
 */
@Component
public class TemplateRegistry {

    private static final String PLUGIN_JSON = "plugin.json";
    private static final String TEMPLATE_CATALOG_TYPE = "template";

    private final ObjectMapper objectMapper;
    private final List<Path> searchRoots;

    @Autowired
    public TemplateRegistry(ObjectMapper objectMapper) {
        this(objectMapper, defaultSearchRoots(Paths.get(System.getProperty("user.dir"))));
    }

    TemplateRegistry(ObjectMapper objectMapper, List<Path> searchRoots) {
        this.objectMapper = objectMapper;
        this.searchRoots = List.copyOf(searchRoots);
    }

    /**
     * Resolve a templateId to an absolute directory path.
     *
     * @param templateId the template identifier
     * @return absolute path, or null if template not found
     */
    public String resolveAbsolutePath(String templateId) {
        TemplateDef def = getTemplate(templateId);
        return def != null ? def.absolutePath() : null;
    }

    /**
     * Get template definition by ID.
     *
     * @param templateId the template identifier
     * @return template definition, or null if not found
     */
    public TemplateDef getTemplate(String templateId) {
        return discoverTemplates().get(templateId);
    }

    /**
     * List all available templates.
     *
     * @return unmodifiable list of all template definitions
     */
    public List<TemplateDef> listAll() {
        return List.copyOf(discoverTemplates().values());
    }

    private LinkedHashMap<String, TemplateDef> discoverTemplates() {
        LinkedHashMap<String, TemplateDef> templates = new LinkedHashMap<>();
        for (Path root : searchRoots) {
            discoverUnderRoot(root, templates);
        }
        return templates;
    }

    private void discoverUnderRoot(Path root, Map<String, TemplateDef> templates) {
        Path pluginsDir = root.resolve("plugins");
        if (!Files.isDirectory(pluginsDir)) {
            return;
        }

        discoverPluginChildren(root, pluginsDir, false, templates);

        Path legacyTemplatesDir = pluginsDir.resolve("templates");
        if (Files.isDirectory(legacyTemplatesDir)) {
            discoverPluginChildren(root, legacyTemplatesDir, true, templates);
        }
    }

    private void discoverPluginChildren(Path root, Path parentDir, boolean legacyTemplateDir, Map<String, TemplateDef> templates) {
        List<Path> children = listDirectories(parentDir);
        for (Path pluginDir : children) {
            TemplateDef def = readTemplate(root, pluginDir, legacyTemplateDir);
            if (def != null) {
                templates.putIfAbsent(def.id(), def);
            }
        }
    }

    private TemplateDef readTemplate(Path root, Path pluginDir, boolean legacyTemplateDir) {
        Path manifestPath = pluginDir.resolve(PLUGIN_JSON);
        if (!Files.isRegularFile(manifestPath)) {
            return null;
        }

        JsonNode manifest;
        try {
            manifest = objectMapper.readTree(manifestPath.toFile());
        } catch (IOException ignored) {
            return null;
        }

        if (!isTemplateManifest(manifest, legacyTemplateDir)) {
            return null;
        }

        String id = pluginDir.getFileName().toString();
        String name = textValue(manifest, "displayName", id);
        String namespace = textValue(manifest, "namespace", "");
        String relativePath = toRelativePath(root, pluginDir);

        return new TemplateDef(id, name, relativePath, namespace, pluginDir.normalize().toString());
    }

    private boolean isTemplateManifest(JsonNode manifest, boolean legacyTemplateDir) {
        if (legacyTemplateDir) {
            return true;
        }

        String catalogType = textValue(manifest, "catalogType", "");
        if (TEMPLATE_CATALOG_TYPE.equalsIgnoreCase(catalogType)) {
            return true;
        }

        String pluginType = textValue(manifest, "pluginType", "");
        return TEMPLATE_CATALOG_TYPE.equalsIgnoreCase(pluginType);
    }

    private static String textValue(JsonNode node, String fieldName, String fallback) {
        JsonNode value = node.get(fieldName);
        return value != null && !value.isNull() && !value.asText().isBlank() ? value.asText() : fallback;
    }

    private static String toRelativePath(Path root, Path pluginDir) {
        if (pluginDir.startsWith(root)) {
            return root.relativize(pluginDir).toString().replace('\\', '/');
        }
        return pluginDir.normalize().toString().replace('\\', '/');
    }

    private static List<Path> listDirectories(Path parentDir) {
        try (var stream = Files.list(parentDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private static List<Path> defaultSearchRoots(Path workingDir) {
        List<Path> candidates = new ArrayList<>();
        Path normalized = workingDir.normalize();
        Path parent = normalized.getParent();
        Path grandParent = parent != null ? parent.getParent() : null;
        Path repoRoot = nearestRepoRoot(normalized);

        addCandidate(candidates, normalized);
        addCandidate(candidates, parent);
        addCandidate(candidates, grandParent);

        if (repoRoot != null) {
            addCandidate(candidates, repoRoot);

            Path workspaceRoot = repoRoot.getParent();
            if (workspaceRoot != null && "auraboot-enterprise".equals(repoRoot.getFileName().toString())) {
                addCandidate(candidates, workspaceRoot.resolve("auraboot"));
            }
        }

        return candidates;
    }

    private static Path nearestRepoRoot(Path start) {
        Path cursor = start;
        while (cursor != null) {
            if (Files.isDirectory(cursor.resolve("plugins"))
                    && (Files.isDirectory(cursor.resolve(".git")) || Files.isRegularFile(cursor.resolve(".git")))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private static void addCandidate(List<Path> candidates, Path candidate) {
        if (candidate == null) {
            return;
        }

        Path normalized = candidate.normalize();
        if (!Files.isDirectory(normalized)) {
            return;
        }
        if (candidates.stream().anyMatch(existing -> existing.equals(normalized))) {
            return;
        }
        candidates.add(normalized);
    }

    /**
     * Template definition record.
     *
     * @param id           unique template identifier
     * @param name         human-readable display name
     * @param relativePath relative path from repository root to the template directory
     * @param namespace    plugin namespace prefix
     * @param absolutePath absolute directory path on disk
     */
    public record TemplateDef(
            String id,
            String name,
            String relativePath,
            String namespace,
            @JsonIgnore
            String absolutePath
    ) {
        public TemplateDef {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(relativePath, "relativePath");
            Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(absolutePath, "absolutePath");
        }
    }
}
