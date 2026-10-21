package com.auraboot.framework.plugin.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ModelFieldRenameMigratorTest {
    private final ObjectMapper json = new ObjectMapper();
    private String mapping(String from, String to) {
        return "{\"schemaVersion\":2,\"modelFieldRenames\":[{\"modelCode\":\"contact\",\"fromField\":\"" + from + "\",\"toField\":\"" + to + "\"}]}";
    }
    @Test void legacyMappingsAreNotReinterpreted() throws Exception {
        assertTrue(ModelFieldRenameMigrator.parse(json.readTree("{\"identifiers\":[{\"from\":\"a\",\"to\":\"b\"}]}" )).isEmpty());
    }
    @Test void exactScopedMappingPreservesCategoryIdentity() throws Exception {
        var result = ModelFieldRenameMigrator.parse(json.readTree(mapping("shared_name", "contact_name")));
        assertEquals(java.util.List.of(new ModelFieldRenameMigrator.Rename("contact", "shared_name", "contact_name")), result);
    }
    @ParameterizedTest @ValueSource(strings={"pid", "tenant_id", "row_version", "created_at", "updated_by", "contact_name", "name;drop_table"})
    void infrastructureSameAndUnsafeFieldsAreRejected(String from) {
        assertThrows(IllegalArgumentException.class, () -> ModelFieldRenameMigrator.parse(json.readTree(mapping(from,"contact_name"))));
    }
    @ParameterizedTest @ValueSource(strings={
        "{\"modelFieldRenames\":[]}",
        "{\"schemaVersion\":1,\"modelFieldRenames\":[]}",
        "{\"schemaVersion\":2,\"modelFieldRenames\":[],\"table\":\"mt_contact\"}",
        "{\"schemaVersion\":2,\"modelFieldRenames\":[{\"modelCode\":\"contact\",\"fromField\":\"a\",\"toField\":\"b\",\"tenantId\":2}]}",
        "{\"schemaVersion\":2,\"modelFieldRenames\":[{\"modelCode\":\"contact\",\"fromField\":\"a\",\"toField\":\"b\"},{\"modelCode\":\"contact\",\"fromField\":\"b\",\"toField\":\"c\"}]}"
    }) void scopeAndVersionCannotBeExtendedByCaller(String value) {
        assertThrows(IllegalArgumentException.class, () -> ModelFieldRenameMigrator.parse(json.readTree(value)));
    }
    @Test void losslessUnicodeAndNullArePreservedWithoutTruncation() {
        assertDoesNotThrow(() -> ModelFieldRenameMigrator.validateValue("😀中文",null,3,true));
        assertDoesNotThrow(() -> ModelFieldRenameMigrator.validateValue(null,null,50,false));
        assertDoesNotThrow(() -> ModelFieldRenameMigrator.validateValue("","",50,false));
        assertThrows(IllegalArgumentException.class, () -> ModelFieldRenameMigrator.validateValue("😀中文",null,2,true));
        assertThrows(IllegalArgumentException.class, () -> ModelFieldRenameMigrator.validateValue(null,null,50,true));
        assertThrows(IllegalArgumentException.class, () -> ModelFieldRenameMigrator.validateValue("old","edited",50,true));
        assertThrows(IllegalArgumentException.class, () -> ModelFieldRenameMigrator.validateValue(123,null,50,true));
        assertThrows(IllegalArgumentException.class, () -> ModelFieldRenameMigrator.validateValue("old",123,50,true));
    }
}
