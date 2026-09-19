package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.List;

/** Internal definition evidence for generated files; never a client authorization input. */
final class NamedQueryExportDefinition {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private NamedQueryExportDefinition() { }

    static JsonNode capture(NamedQuery query, List<NamedQueryField> fields) {
        return capture(query, fields, List.of());
    }

    static JsonNode capture(NamedQuery query, List<NamedQueryField> fields, List<String> rowScope) {
        return capture(query, fields, rowScope, JSON.createObjectNode());
    }

    static JsonNode capture(NamedQuery query, List<NamedQueryField> fields, List<String> rowScope, JsonNode protection) {
        ObjectNode definition = JSON.valueToTree(query);
        definition.retain(List.of("pid", "tenantId", "code", "resourceCode", "actionCode", "fromSql",
                "connectorPid", "connectorEndpointCode", "baseWhere", "defaultOrder", "policy"));
        definition.set("rowScope", JSON.valueToTree(rowScope));
        definition.set("fieldProtection", protection);
        var output = definition.putArray("fields");
        fields.stream().sorted(Comparator.comparing(NamedQueryField::getFieldCode)).forEach(field -> {
            ObjectNode value = JSON.valueToTree(field);
            value.remove(List.of("createdAt", "updatedAt"));
            output.add(value);
        });
        try {
            // Normalize numeric node types to the representation read back from JSONB.
            return JSON.readTree(JSON.writeValueAsBytes(definition));
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot capture export definition", error);
        }
    }
}
