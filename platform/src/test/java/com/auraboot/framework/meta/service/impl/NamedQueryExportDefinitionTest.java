package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.entity.NamedQueryPolicy;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NamedQueryExportDefinitionTest {
    @Test
    void statusAndPublishMetadataDoNotHidePolicyOrSourceChanges() {
        NamedQuery query = new NamedQuery();
        query.setPid("query");
        query.setCode("orders");
        query.setFromSql("orders");
        query.setStatus("published");
        var original = NamedQueryExportDefinition.capture(query, List.of());
        query.setCurrentVersion(2);
        query.setPublishedAt(java.time.Instant.now());
        query.setStatus("deprecated");
        assertEquals(original, NamedQueryExportDefinition.capture(query, List.of()));
        query.setPolicy(new NamedQueryPolicy());
        assertNotEquals(original, NamedQueryExportDefinition.capture(query, List.of()));
        query.setPolicy(null);
        query.setFromSql("private_orders");
        assertNotEquals(original, NamedQueryExportDefinition.capture(query, List.of()));
    }

    @Test
    void fieldExpressionChangesInvalidateDefinition() {
        NamedQuery query = new NamedQuery();
        NamedQueryField field = new NamedQueryField();
        field.setFieldCode("value");
        field.setColumnExpr("public_value");
        var original = NamedQueryExportDefinition.capture(query, List.of(field));
        field.setColumnExpr("private_value");
        assertNotEquals(original, NamedQueryExportDefinition.capture(query, List.of(field)));
    }
    @Test
    void definitionMatchesItsPersistedJsonRepresentation() throws Exception {
        NamedQuery query = new NamedQuery();
        query.setTenantId(1L);
        NamedQueryField field = new NamedQueryField();
        field.setId(2L);
        field.setFieldCode("value");
        var snapshot = NamedQueryExportDefinition.capture(query, List.of(field));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(snapshot, json.readTree(json.writeValueAsBytes(snapshot)));
    }
    @Test
    void effectiveRowScopeChangeInvalidatesDefinition() {
        NamedQuery query = new NamedQuery();
        var all = NamedQueryExportDefinition.capture(query, List.of(), List.of());
        var self = NamedQueryExportDefinition.capture(query, List.of(), List.of("created_by = 123"));
        assertNotEquals(all, self);
    }
}
