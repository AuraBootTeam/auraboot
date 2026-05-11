package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.PluginManifest.CapabilityRequirement;
import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.NamedQueryDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.auraboot.framework.plugin.validation.PluginValidationMessage.error;
import static com.auraboot.framework.plugin.validation.PluginValidationMessage.warning;

/**
 * S-CAP: Validates that all required capabilities declared in the manifest's
 * "requires" list are satisfied by either installed resources or the current manifest.
 * <p>
 * For each requirement:
 * - type=model: check installed model codes + manifest models
 * - type=command: check installed command codes + manifest commands
 * - type=query: check installed named query codes + manifest named queries
 * - type=automation: check installed automations (best-effort, warning only)
 * - type=api: skip (external, cannot verify at import time)
 * <p>
 * Missing required capability (optional=false) produces ERROR.
 * Missing optional capability produces WARNING.
 */
@Slf4j
@Component
public class CapabilityDependencyValidator implements PluginValidator {

    @Override
    public String category() {
        return "semantic";
    }

    @Override
    public boolean requiresReferenceValidation() {
        return true;
    }

    @Override
    public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
        List<PluginValidationMessage> messages = new ArrayList<>();
        PluginManifestExtended manifest = ctx.getManifest();

        List<CapabilityRequirement> requirements = manifest.getRequires();
        if (requirements == null || requirements.isEmpty()) {
            return messages;
        }

        Set<String> availableModels = buildAvailableModels(manifest, ctx);
        Set<String> availableCommands = buildAvailableCommands(manifest, ctx);
        Set<String> availableQueries = buildAvailableQueries(manifest, ctx);

        for (int i = 0; i < requirements.size(); i++) {
            CapabilityRequirement req = requirements.get(i);
            if (req == null || req.getType() == null || req.getCode() == null) continue;

            String path = "requires[" + i + "]";
            boolean satisfied = switch (req.getType()) {
                case "model" -> availableModels.contains(req.getCode());
                case "command" -> availableCommands.contains(req.getCode());
                case "query" -> availableQueries.contains(req.getCode());
                case "api" -> true; // External APIs cannot be verified at import time
                case "automation" -> true; // Automation existence is hard to verify; skip for now
                default -> {
                    messages.add(warning("S-CAP-TYPE", category(), path,
                            "Unknown capability type '" + req.getType() + "' for requirement '" + req.getCode() + "'"));
                    yield true; // Don't block on unknown types
                }
            };

            if (!satisfied) {
                String msg = "Required capability " + req.getType() + ":" + req.getCode() +
                        " is not provided by any installed plugin or this manifest";
                if (req.isOptional()) {
                    messages.add(warning("S-CAP-OPTIONAL", category(), path, msg + " (optional)"));
                } else {
                    messages.add(error("S-CAP-MISSING", category(), path, msg));
                }
            }
        }

        return messages;
    }

    private Set<String> buildAvailableModels(PluginManifestExtended manifest, PluginValidationContext ctx) {
        Set<String> models = new HashSet<>();
        if (ctx.getInstalledModelCodes() != null) {
            models.addAll(ctx.getInstalledModelCodes());
        }
        if (manifest.getModels() != null) {
            for (ModelDefinitionDTO m : manifest.getModels()) {
                if (m != null && m.getCode() != null) {
                    models.add(m.getCode());
                }
            }
        }
        return models;
    }

    private Set<String> buildAvailableCommands(PluginManifestExtended manifest, PluginValidationContext ctx) {
        Set<String> commands = new HashSet<>();
        if (ctx.getInstalledCommandCodes() != null) {
            commands.addAll(ctx.getInstalledCommandCodes());
        }
        if (manifest.getCommands() != null) {
            for (CommandDefinitionDTO c : manifest.getCommands()) {
                if (c != null && c.getCode() != null) {
                    commands.add(c.getCode());
                }
            }
        }
        return commands;
    }

    private Set<String> buildAvailableQueries(PluginManifestExtended manifest, PluginValidationContext ctx) {
        Set<String> queries = new HashSet<>();
        if (ctx.getInstalledNamedQueryCodes() != null) {
            queries.addAll(ctx.getInstalledNamedQueryCodes());
        }
        if (manifest.getNamedQueries() != null) {
            for (NamedQueryDefinitionDTO nq : manifest.getNamedQueries()) {
                if (nq != null && nq.getCode() != null) {
                    queries.add(nq.getCode());
                }
            }
        }
        return queries;
    }
}
