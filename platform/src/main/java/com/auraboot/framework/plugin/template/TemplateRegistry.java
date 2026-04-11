package com.auraboot.framework.plugin.template;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Registry of built-in application templates.
 *
 * Resolves a templateId (e.g., "crm-quick-start") to an absolute directory path
 * on the server. Currently uses a hardcoded list of built-in templates under
 * the {@code plugins/templates/} directory. Future: extend to support DB-backed
 * marketplace templates, .abp packages, and git sources.
 */
@Component
public class TemplateRegistry {

    /** Built-in template definitions: id -> relative directory path */
    private static final Map<String, TemplateDef> BUILTIN_TEMPLATES;

    static {
        Map<String, TemplateDef> map = new LinkedHashMap<>();
        map.put("crm-quick-start", new TemplateDef("crm-quick-start", "CRM Quick Start", "plugins/templates/crm-quick-start", "tcrm"));
        map.put("project-management", new TemplateDef("project-management", "Project Management", "plugins/templates/project-management", "tpm"));
        map.put("asset-management", new TemplateDef("asset-management", "Asset Management", "plugins/templates/asset-management", "tasset"));
        map.put("simple-inventory", new TemplateDef("simple-inventory", "Simple Inventory", "plugins/templates/simple-inventory", "tinv"));
        map.put("hr-essentials", new TemplateDef("hr-essentials", "HR Essentials", "plugins/templates/hr-essentials", "thr"));
        BUILTIN_TEMPLATES = Collections.unmodifiableMap(map);
    }

    /**
     * Resolve a templateId to an absolute directory path.
     *
     * @param templateId the template identifier
     * @return absolute path, or null if template not found
     */
    public String resolveAbsolutePath(String templateId) {
        TemplateDef def = BUILTIN_TEMPLATES.get(templateId);
        if (def == null) {
            return null;
        }

        Path configuredPath = Paths.get(def.relativePath());
        if (configuredPath.isAbsolute()) {
            return configuredPath.normalize().toString();
        }

        Path workingDir = Paths.get(System.getProperty("user.dir")).normalize();
        String relativePath = def.relativePath();

        List<Path> candidates = new ArrayList<>();
        candidates.add(workingDir.resolve(relativePath));

        Path parent = workingDir.getParent();
        if (parent != null) {
            candidates.add(parent.resolve(relativePath));

            Path grandParent = parent.getParent();
            if (grandParent != null) {
                candidates.add(grandParent.resolve(relativePath));
                candidates.add(grandParent.resolve("auraboot").resolve(relativePath));
            }
        }

        for (Path candidate : candidates) {
            Path normalized = candidate.normalize();
            if (Files.isDirectory(normalized)) {
                return normalized.toString();
            }
        }

        return candidates.getFirst().normalize().toString();
    }

    /**
     * Get template definition by ID.
     *
     * @param templateId the template identifier
     * @return template definition, or null if not found
     */
    public TemplateDef getTemplate(String templateId) {
        return BUILTIN_TEMPLATES.get(templateId);
    }

    /**
     * List all available templates.
     *
     * @return unmodifiable list of all template definitions
     */
    public List<TemplateDef> listAll() {
        return new ArrayList<>(BUILTIN_TEMPLATES.values());
    }

    /**
     * Template definition record.
     *
     * @param id           unique template identifier
     * @param name         human-readable display name
     * @param relativePath relative path from project root to the template directory
     * @param namespace    plugin namespace prefix
     */
    public record TemplateDef(
            String id,
            String name,
            String relativePath,
            String namespace
    ) {}
}
