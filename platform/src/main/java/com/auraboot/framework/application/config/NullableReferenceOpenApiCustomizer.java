package com.auraboot.framework.application.config;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Preserve nullable reference semantics when Springdoc serializes OpenAPI 3.1. */
@Component
public final class NullableReferenceOpenApiCustomizer implements PropertyCustomizer {
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Schema customize(Schema schema, AnnotatedType type) {
        if (schema == null || schema.get$ref() == null) return schema;
        boolean nullable = Boolean.TRUE.equals(schema.getNullable())
                || "null".equals(schema.getType())
                || schema.getTypes() != null && schema.getTypes().contains("null");
        if (!nullable) return schema;

        // A sibling type:null does not relax a $ref: JSON Schema applies both constraints.
        schema.setNullable(null);
        if ("null".equals(schema.getType())) schema.setType(null);
        if (schema.getTypes() != null) {
            var nonNullTypes = new java.util.LinkedHashSet<String>(schema.getTypes());
            nonNullTypes.remove("null");
            schema.setTypes(nonNullTypes.isEmpty() ? null : nonNullTypes);
        }
        var nullableBranch = new JsonSchema();
        nullableBranch.setTypes(Set.of("null"));
        return new ComposedSchema().addAnyOfItem(schema).addAnyOfItem(nullableBranch);
    }
}
