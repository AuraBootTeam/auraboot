package com.auraboot.framework.plugin.extension;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ValidatorExtensionBoundaryTest {
    @Test
    void contextBuilderAndResultFactoriesPreserveStructuredFieldErrors() {
        var data = Map.<String, Object>of("amount", 5); var params = Map.<String, Object>of("min", 10); var settings = Map.<String, Object>of("locale", "en-US");
        var context = ValidatorExtension.ValidationContext.builder().tenantId(7L).pluginId("plugin").namespace("ns")
            .validatorKey("ns:amount").fieldCode("amount").value(5).recordData(data).validatorParams(params).settings(settings).build();
        assertEquals(7L, context.tenantId()); assertEquals("plugin", context.pluginId()); assertEquals("ns", context.namespace());
        assertEquals("ns:amount", context.validatorKey()); assertEquals("amount", context.fieldCode()); assertEquals(5, context.value());
        assertSame(data, context.recordData()); assertSame(params, context.validatorParams()); assertSame(settings, context.settings());
        var defaults = ValidatorExtension.ValidationContext.builder().build();
        assertEquals(Map.of(), defaults.recordData()); assertEquals(Map.of(), defaults.validatorParams()); assertEquals(Map.of(), defaults.settings());
        assertTrue(ValidatorExtension.ValidationResult.success().valid());
        assertEquals(List.of(), ValidatorExtension.ValidationResult.success().errors());
        var message = ValidatorExtension.ValidationResult.error("Too small"); assertFalse(message.valid());
        assertEquals(new ValidatorExtension.ValidationError(null, "Too small"), message.errors().get(0));
        var field = ValidatorExtension.ValidationResult.error("amount", "Too small"); assertFalse(field.valid());
        assertEquals(new ValidatorExtension.ValidationError("amount", "Too small"), field.errors().get(0));
        assertFalse(ValidatorExtension.ValidationResult.errors(field.errors()).valid());
        assertTrue(ValidatorExtension.ValidationResult.errors(List.of()).valid());
    }

    @Test
    void defaultValidatorSelectionHasAnOrderAndDoesNotFailFast() {
        var validator = new ValidatorExtension() { public String getValidatorKey() { return "ns:amount"; } public ValidationResult validate(ValidationContext context) { return ValidationResult.success(); } };
        assertTrue(validator.supports("ns:amount")); assertFalse(validator.supports("ns:other")); assertFalse(validator.supports(null));
        assertEquals(100, validator.getOrder()); assertFalse(validator.isFailFast());
    }
}
