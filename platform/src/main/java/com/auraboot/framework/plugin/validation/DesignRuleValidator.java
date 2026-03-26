package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Layer 3 — Design Rule Validation.
 * Checks architectural quality beyond structural/semantic correctness:
 * - Page coverage: every Model should have List + Detail/Form pages
 * - Risk declaration: write commands should declare cmd_risk_level
 * - Commands with sideEffects at L2+ should have agent_hint or descriptions
 */
@Component
public class DesignRuleValidator implements PluginValidator {

    @Override
    public String category() {
        return "governance";
    }

    @Override
    public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
        List<PluginValidationMessage> messages = new ArrayList<>();
        PluginManifestExtended manifest = ctx.getManifest();

        checkPageCoverage(manifest, messages);
        checkRiskDeclarations(manifest, messages);

        return messages;
    }

    /**
     * Check that every ENTITY model has at least a List page and a Form/Detail page.
     */
    private void checkPageCoverage(PluginManifestExtended manifest, List<PluginValidationMessage> messages) {
        List<ModelDefinitionDTO> models = manifest.getModels();
        List<PageSchemaDTO> pages = manifest.getPages();
        if (models == null || models.isEmpty()) return;

        Set<String> modelCodesWithListPage = new HashSet<>();
        Set<String> modelCodesWithFormPage = new HashSet<>();

        if (pages != null) {
            for (PageSchemaDTO page : pages) {
                String modelCode = page.getModelCode();
                if (modelCode == null) continue;
                String pageType = page.getPageType();
                if ("list".equals(pageType)) {
                    modelCodesWithListPage.add(modelCode);
                } else if ("form".equals(pageType) || "detail".equals(pageType)) {
                    modelCodesWithFormPage.add(modelCode);
                }
            }
        }

        for (ModelDefinitionDTO model : models) {
            String modelType = model.getModelType();
            // Only check ENTITY models (skip VIEW, CONFIG, etc.)
            if (!"entity".equalsIgnoreCase(modelType)) continue;

            String code = model.getCode();
            if (!modelCodesWithListPage.contains(code)) {
                messages.add(PluginValidationMessage.info("D-PAGE-LIST",
                        "design",
                        "Model '" + code + "' has no List page — users won't be able to browse records",
                        "models/" + code));
            }
            if (!modelCodesWithFormPage.contains(code)) {
                messages.add(PluginValidationMessage.info("D-PAGE-FORM",
                        "design",
                        "Model '" + code + "' has no Form/Detail page — users won't be able to view/edit records",
                        "models/" + code));
            }
        }
    }

    /**
     * Check that write commands have risk_level declared, especially those with side effects.
     */
    @SuppressWarnings("unchecked")
    private void checkRiskDeclarations(PluginManifestExtended manifest, List<PluginValidationMessage> messages) {
        List<CommandDefinitionDTO> commands = manifest.getCommands();
        if (commands == null) return;

        for (int i = 0; i < commands.size(); i++) {
            CommandDefinitionDTO cmd = commands.get(i);
            Map<String, Object> execConfig = cmd.getConsolidatedExecutionConfig();
            String type = (String) execConfig.get("type");
            String code = cmd.getCode();

            // Skip read-only commands
            if ("query".equals(type)) continue;

            // Check: DELETE commands should have risk acknowledged
            if ("delete".equals(type) || "bulk_delete".equals(type)) {
                Object riskLevel = execConfig.get("riskLevel");
                if (riskLevel == null && cmd.getUnknownFields() != null
                        && !cmd.getUnknownFields().containsKey("cmd_risk_level")) {
                    messages.add(PluginValidationMessage.warning("D-RISK-DELETE",
                            "design",
                            "commands[" + i + "]",
                            "Command '" + code + "' is a DELETE operation but has no explicit risk_level. " +
                                    "DELETE commands are auto-classified as L4 (irreversible)."));
                }
            }

            // Check: commands with sideEffects should have description
            Object sideEffects = execConfig.get("sideEffects");
            if (sideEffects instanceof List<?> list && !list.isEmpty()) {
                boolean hasDescription = hasNonBlankField(cmd, "agentHint")
                        || hasNonBlankField(cmd, "sideEffectDescription")
                        || (cmd.getDescription() != null && cmd.getDescription().length() > 20);
                if (!hasDescription) {
                    messages.add(PluginValidationMessage.warning("D-SIDE-EFFECT-DESC",
                            "design",
                            "commands[" + i + "]",
                            "Command '" + code + "' has " + list.size() + " side effects but no description. " +
                                    "Add agent_hint or side_effect_description for Agent readability."));
                }
            }
        }
    }

    private boolean hasNonBlankField(CommandDefinitionDTO cmd, String fieldName) {
        Map<String, Object> unknown = cmd.getUnknownFields();
        if (unknown == null) return false;
        Object val = unknown.get(fieldName);
        return val instanceof String s && !s.isBlank();
    }
}
