package com.auraboot.framework.plugin.validation;

import java.util.List;

/**
 * Interface for plugin validation rules.
 * Each validator checks one aspect of a plugin manifest.
 */
public interface PluginValidator {

    /**
     * Validate the plugin manifest against this rule.
     *
     * @param ctx the validation context
     * @return list of validation messages (errors, warnings, infos)
     */
    List<PluginValidationMessage> validate(PluginValidationContext ctx);

    /**
     * The category of this validator: "structural", "semantic", or "governance".
     */
    String category();

    /**
     * Whether this validator depends on external references such as installed
     * plugins, command-handler registries, or cross-resource capability state.
     */
    default boolean requiresReferenceValidation() {
        return false;
    }
}
