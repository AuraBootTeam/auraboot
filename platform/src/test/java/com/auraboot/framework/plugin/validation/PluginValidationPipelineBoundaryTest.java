package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PluginValidationPipelineBoundaryTest {
    @Test
    void referenceChecksCanBeDisabledInBothLayersWithoutSkippingLocalValidators() {
        var calls = new ArrayList<String>();
        var pipeline = new PluginValidationPipeline(List.of(validator("governance", true, calls, false),
            validator("semantic", true, calls, false), validator("semantic", false, calls, false),
            validator("governance", false, calls, false)));
        var context = context(); context.setValidateReferences(false);
        assertTrue(pipeline.validate(context).isValid());
        assertEquals(List.of("semantic:false", "governance:false"), calls);
        calls.clear(); context.setValidateReferences(null);
        assertTrue(pipeline.validate(context).isValid());
        assertEquals(List.of("semantic:true", "semantic:false", "governance:true", "governance:false"), calls);
    }

    @Test
    void semanticExceptionsAreBlockingAndPreventGovernance() {
        var calls = new ArrayList<String>();
        var result = new PluginValidationPipeline(List.of(validator("semantic", false, calls, true),
            validator("governance", false, calls, false))).validate(context());
        assertFalse(result.isValid()); assertEquals(1, result.getErrorCount());
        assertEquals("V-INTERNAL", result.getMessages().get(0).getCode());
        assertEquals("semantic", result.getMessages().get(0).getCategory());
        assertEquals(List.of("semantic:false"), calls);
    }

    @Test
    void governanceExceptionsAreBlockingAndOtherValidatorsStillRun() {
        var calls = new ArrayList<String>();
        var result = new PluginValidationPipeline(List.of(validator("governance", false, calls, true),
            validator("governance", true, calls, false))).validate(context());
        assertFalse(result.isValid()); assertEquals(1, result.getErrorCount());
        assertEquals("governance", result.getMessages().get(0).getCategory());
        assertEquals(List.of("governance:false", "governance:true"), calls);
    }

    @Test
    void defaultValidatorIsIndependentOfReferences() {
        PluginValidator validator = new PluginValidator() {
            public String category() { return "semantic"; }
            public List<PluginValidationMessage> validate(PluginValidationContext ctx) { return List.of(); }
        };
        assertFalse(validator.requiresReferenceValidation());
    }

    private PluginValidationContext context() {
        return PluginValidationContext.builder().manifest(new PluginManifestExtended()).build();
    }
    private PluginValidator validator(String category, boolean references, List<String> calls, boolean fail) {
        return new PluginValidator() {
            public String category() { return category; }
            public boolean requiresReferenceValidation() { return references; }
            public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
                calls.add(category + ":" + references);
                if (fail) throw new IllegalArgumentException("Invalid input");
                return List.of();
            }
        };
    }
}
