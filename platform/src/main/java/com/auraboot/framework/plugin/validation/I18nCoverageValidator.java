package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.I18nDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelFieldBindingDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.auraboot.framework.plugin.validation.PluginValidationMessage.info;

/**
 * G-I18N: Checks i18n key coverage for models and fields.
 * <p>
 * For each model, expects:
 *   model.{code}._meta.label (zh-CN and en-US)
 * For each field binding, expects:
 *   model.{modelCode}.{fieldCode}.label (zh-CN and en-US)
 * <p>
 * Reports as info-level messages (not blocking).
 */
@Component
public class I18nCoverageValidator implements PluginValidator {

    @Override
    public String category() {
        return "governance";
    }

    @Override
    public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
        List<PluginValidationMessage> messages = new ArrayList<>();
        PluginManifestExtended manifest = ctx.getManifest();

        // Collect all i18n keys that have at least one translation
        Set<String> coveredKeys = new HashSet<>();

        if (manifest.getI18nResources() != null) {
            for (I18nDefinitionDTO i18n : manifest.getI18nResources()) {
                if (i18n == null || i18n.getKey() == null) continue;
                // A key is covered if it has any translation (zh-CN, en-US, or any other)
                if (!i18n.getAllTranslations().isEmpty()) {
                    coveredKeys.add(i18n.getKey());
                }
            }
        }

        // Check model labels
        if (manifest.getModels() != null) {
            for (ModelDefinitionDTO model : manifest.getModels()) {
                if (model == null || model.getCode() == null) continue;
                String key = "model." + model.getCode() + "._meta.label";
                if (!coveredKeys.contains(key)) {
                    messages.add(info("G-I18N-MODEL", category(),
                            "Missing i18n key '" + key + "' for model '" + model.getCode() + "'"));
                }
            }
        }

        // Check field labels
        if (manifest.getModelFieldBindings() != null) {
            for (ModelFieldBindingDTO binding : manifest.getModelFieldBindings()) {
                if (binding == null || binding.getModelCode() == null || binding.getFieldCode() == null) continue;
                String key = "model." + binding.getModelCode() + "." + binding.getFieldCode() + ".label";
                if (!coveredKeys.contains(key)) {
                    messages.add(info("G-I18N-FIELD", category(),
                            "Missing i18n key '" + key + "' for field '" +
                                    binding.getModelCode() + "." + binding.getFieldCode() + "'"));
                }
            }
        }

        return messages;
    }
}
