package com.auraboot.framework.application.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Preserve explicitly nullable component references after Springdoc assembles its schema graph. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class NullableReferenceOpenApiCustomizer implements OpenApiCustomizer {
    @Override
    public void customise(OpenAPI document) {
        if (document.getComponents() == null || document.getComponents().getSchemas() == null) return;
        Map<Schema, Schema> visited = new IdentityHashMap<>();
        document.getComponents().getSchemas().replaceAll((name, schema) -> visit(schema, visited));
    }

    private Schema visit(Schema schema, Map<Schema, Schema> visited) {
        if (schema == null) return null;
        if (visited.containsKey(schema)) return visited.get(schema);
        visited.put(schema, schema);
        if (schema.getProperties() != null) {
            ((Map<String, Schema>) schema.getProperties()).replaceAll((name, child) -> visit(child, visited));
        }
        if (schema.getItems() != null) schema.setItems(visit(schema.getItems(), visited));
        if (schema.getAdditionalProperties() instanceof Schema child) {
            schema.setAdditionalProperties(visit(child, visited));
        }
        if (schema.getAllOf() != null) schema.setAllOf(((java.util.List<Schema>) schema.getAllOf()).stream().map(child -> visit(child, visited)).toList());
        if (schema.getAnyOf() != null) schema.setAnyOf(((java.util.List<Schema>) schema.getAnyOf()).stream().map(child -> visit(child, visited)).toList());
        if (schema.getOneOf() != null) schema.setOneOf(((java.util.List<Schema>) schema.getOneOf()).stream().map(child -> visit(child, visited)).toList());
        if (schema.getNot() != null) schema.setNot(visit(schema.getNot(), visited));
        var result = nullableReference(schema);
        visited.put(schema, result);
        return result;
    }

    Schema nullableReference(Schema schema) {
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
