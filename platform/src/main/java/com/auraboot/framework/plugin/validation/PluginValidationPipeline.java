package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Orchestrates the three-layer plugin validation pipeline.
 * <p>
 * Layer 1 (Structural): JSON syntax + manifest schema — handled by existing code before this pipeline.
 * Layer 2 (Semantic): Cross-reference, namespace, executionConfig, page schema.
 * Layer 3 (Governance): i18n coverage, circular dependencies.
 * <p>
 * Short-circuit: if Layer 1 produced errors, Layer 2/3 are skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginValidationPipeline {

    private final List<PluginValidator> validators;

    /**
     * Run all validators against the given context.
     * Validators are auto-detected via Spring component scanning.
     *
     * @param ctx the validation context with manifest + tenant state
     * @return aggregated validation result
     */
    public PluginValidationResult validate(PluginValidationContext ctx) {
        PluginValidationResult result = PluginValidationResult.empty();
        boolean validateReferences = !Boolean.FALSE.equals(ctx.getValidateReferences());

        // Run semantic validators first, then governance
        List<PluginValidator> semanticValidators = validators.stream()
                .filter(v -> "semantic".equals(v.category()))
                .filter(v -> validateReferences || !v.requiresReferenceValidation())
                .toList();
        List<PluginValidator> governanceValidators = validators.stream()
                .filter(v -> "governance".equals(v.category()))
                .filter(v -> validateReferences || !v.requiresReferenceValidation())
                .toList();

        // Layer 2: Semantic
        for (PluginValidator validator : semanticValidators) {
            try {
                List<PluginValidationMessage> messages = validator.validate(ctx);
                result.addAll(messages);
            } catch (Exception e) {
                log.warn("Validator {} threw exception: {}", validator.getClass().getSimpleName(), e.getMessage());
                result.addMessage(PluginValidationMessage.warning("V-INTERNAL", "semantic",
                        "Validator " + validator.getClass().getSimpleName() + " failed: " + e.getMessage()));
            }
        }

        // Short-circuit: if semantic errors found, skip governance
        if (result.getErrorCount() > 0) {
            log.info("Plugin validation: {} errors found in semantic layer, skipping governance",
                    result.getErrorCount());
            return result;
        }

        // Layer 3: Governance
        for (PluginValidator validator : governanceValidators) {
            try {
                List<PluginValidationMessage> messages = validator.validate(ctx);
                result.addAll(messages);
            } catch (Exception e) {
                log.warn("Validator {} threw exception: {}", validator.getClass().getSimpleName(), e.getMessage());
                result.addMessage(PluginValidationMessage.warning("V-INTERNAL", "governance",
                        "Validator " + validator.getClass().getSimpleName() + " failed: " + e.getMessage()));
            }
        }

        return result;
    }
}
