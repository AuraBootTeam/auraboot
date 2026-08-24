package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.ValidationContext;
import com.auraboot.framework.meta.dto.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationServiceTransientFieldTest {

    private final ValidationServiceImpl validation = new ValidationServiceImpl(null, null);

    @Test
    void createDoesNotRequireTransientCommandInputOnThePersistedRecord() {
        FieldDefinition transientInput = FieldDefinition.builder()
                .code("price_request_product_id")
                .name("Pricing Product")
                .dataType("string")
                .required(true)
                .virtualType("transient")
                .build();
        FieldDefinition persistedName = FieldDefinition.builder()
                .code("product_name")
                .name("Product Name")
                .dataType("string")
                .required(true)
                .build();
        ModelDefinition model = ModelDefinition.builder()
                .code("opportunity_line")
                .fields(List.of(transientInput, persistedName))
                .build();

        ValidationResult valid = validation.validateData(
                model,
                Map.of("product_name", "Industrial Gateway"),
                ValidationContext.CREATE);
        assertTrue(valid.getValid(), valid.getErrors().toString());

        ValidationResult missingPersisted = validation.validateData(
                model,
                Map.of(),
                ValidationContext.CREATE);
        assertFalse(missingPersisted.getValid());
        assertTrue(missingPersisted.getErrors().stream()
                .anyMatch(error -> error.contains("Product Name")));
        assertTrue(missingPersisted.getErrors().stream()
                .noneMatch(error -> error.contains("Pricing Product")));
    }
}
