package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

import static com.auraboot.framework.plugin.validation.PluginValidationMessage.warning;

/**
 * S-COMPLETE: Validates resource completeness within a plugin manifest.
 * <p>
 * Non-blocking (warnings only) — import still succeeds, but warns about:
 * - Models with a list page but no form page (Create button won't work)
 * - Models with a list page but no create command
 * - Models with commands/pages but no field definitions (bindings)
 */
@Component
public class CompletenessValidator implements PluginValidator {

    private static final String RULE_CODE = "S-COMPLETE";

    @Override
    public String category() {
        return "semantic";
    }

    @Override
    public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
        List<PluginValidationMessage> messages = new ArrayList<>();
        PluginManifestExtended manifest = ctx.getManifest();

        // Collect model codes defined in this plugin
        Set<String> pluginModelCodes = new HashSet<>();
        if (manifest.getModels() != null) {
            for (ModelDefinitionDTO model : manifest.getModels()) {
                if (model.getCode() != null) {
                    pluginModelCodes.add(model.getCode());
                }
            }
        }
        if (pluginModelCodes.isEmpty()) {
            return messages;
        }

        // Build page index: modelCode -> set of pageTypes
        Map<String, Set<String>> modelPageTypes = new HashMap<>();
        if (manifest.getPages() != null) {
            for (PageSchemaDTO page : manifest.getPages()) {
                String mc = page.getModelCode();
                String pt = page.getPageType();
                if (mc != null && pt != null) {
                    modelPageTypes.computeIfAbsent(mc, k -> new HashSet<>())
                            .add(pt.toLowerCase());
                }
            }
        }

        // Build command index: modelCode -> set of command types
        Map<String, Set<String>> modelCommandTypes = new HashMap<>();
        if (manifest.getCommands() != null) {
            for (CommandDefinitionDTO cmd : manifest.getCommands()) {
                String mc = cmd.getModelCode();
                String type = cmd.getType();
                if (mc != null && type != null) {
                    modelCommandTypes.computeIfAbsent(mc, k -> new HashSet<>())
                            .add(type.toLowerCase());
                }
            }
        }

        // Build field binding index: set of modelCodes that have bindings
        Set<String> modelsWithBindings = new HashSet<>();
        if (manifest.getModelFieldBindings() != null) {
            for (ModelFieldBindingDTO binding : manifest.getModelFieldBindings()) {
                if (binding.getModelCode() != null) {
                    modelsWithBindings.add(binding.getModelCode());
                }
            }
        }

        // Check each model for completeness
        for (String modelCode : pluginModelCodes) {
            Set<String> pageTypes = modelPageTypes.getOrDefault(modelCode, Collections.emptySet());
            Set<String> commandTypes = modelCommandTypes.getOrDefault(modelCode, Collections.emptySet());
            boolean hasList = pageTypes.contains("list");
            boolean hasForm = pageTypes.contains("form");
            boolean hasCreate = commandTypes.contains("create");
            boolean hasBindings = modelsWithBindings.contains(modelCode);

            if (hasList && !hasForm) {
                messages.add(warning(RULE_CODE, "semantic",
                        "model '" + modelCode + "'",
                        "Model '" + modelCode + "' has a list page but no form page — " +
                                "the Create button will open a blank form. Add a form page with pageKey '" +
                                modelCode + "_form'."));
            }

            if (hasList && !hasCreate) {
                messages.add(warning(RULE_CODE, "semantic",
                        "model '" + modelCode + "'",
                        "Model '" + modelCode + "' has a list page but no create command — " +
                                "users cannot create new records."));
            }

            if ((hasCreate || hasList) && !hasBindings) {
                // Only warn if installed fields also don't exist
                boolean hasInstalledFields = ctx.getInstalledFieldCodes() != null &&
                        ctx.getInstalledFieldCodes().stream()
                                .anyMatch(fc -> fc.startsWith(modelCode + ".") || fc.contains(modelCode));
                if (!hasInstalledFields) {
                    messages.add(warning(RULE_CODE, "semantic",
                            "model '" + modelCode + "'",
                            "Model '" + modelCode + "' has pages or commands but no field bindings in this plugin — " +
                                    "forms may render empty if fields are not already installed."));
                }
            }
        }

        return messages;
    }
}
