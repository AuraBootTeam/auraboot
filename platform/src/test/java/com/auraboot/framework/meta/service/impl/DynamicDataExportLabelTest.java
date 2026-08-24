package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DynamicDataServiceImpl.buildFieldLabelMap() — the utility
 * that maps field codes to human-readable display labels for export headers.
 */
class DynamicDataExportLabelTest {

    @Test
    void testBuildFieldLabelMap_withDisplayNames() {
        var fields = List.of(
                FieldDefinition.builder().code("amount").displayName("Total Amount").build(),
                FieldDefinition.builder().code("status").displayName("Status").build()
        );
        Map<String, String> map = DynamicDataServiceImpl.buildFieldLabelMap(fields);
        assertEquals(2, map.size());
        assertEquals("Total Amount", map.get("amount"));
        assertEquals("Status", map.get("status"));
    }

    @Test
    void testBuildFieldLabelMap_fallbackToCodeWhenDisplayNameNull() {
        var fields = List.of(
                FieldDefinition.builder().code("code_field").displayName(null).build()
        );
        Map<String, String> map = DynamicDataServiceImpl.buildFieldLabelMap(fields);
        assertEquals("code_field", map.get("code_field"));
    }

    @Test
    void testBuildFieldLabelMap_fallbackToCodeWhenDisplayNameBlank() {
        var fields = List.of(
                FieldDefinition.builder().code("blank_field").displayName("   ").build()
        );
        Map<String, String> map = DynamicDataServiceImpl.buildFieldLabelMap(fields);
        assertEquals("blank_field", map.get("blank_field"));
    }

    @Test
    void testBuildFieldLabelMap_emptyList() {
        Map<String, String> map = DynamicDataServiceImpl.buildFieldLabelMap(Collections.emptyList());
        assertTrue(map.isEmpty());
    }

    @Test
    void testBuildFieldLabelMap_nullList() {
        Map<String, String> map = DynamicDataServiceImpl.buildFieldLabelMap(null);
        assertTrue(map.isEmpty());
    }

    @Test
    void testBuildFieldLabelMap_mixedDisplayNames() {
        var fields = List.of(
                FieldDefinition.builder().code("amount").displayName("Total Amount").build(),
                FieldDefinition.builder().code("status").displayName(null).build(),
                FieldDefinition.builder().code("notes").displayName("").build(),
                FieldDefinition.builder().code("date").displayName("Order Date").build()
        );
        Map<String, String> map = DynamicDataServiceImpl.buildFieldLabelMap(fields);
        assertEquals(4, map.size());
        assertEquals("Total Amount", map.get("amount"));
        assertEquals("status", map.get("status"));        // fallback
        assertEquals("notes", map.get("notes"));           // fallback (empty)
        assertEquals("Order Date", map.get("date"));
    }

    @Test
    void materializeReferenceDisplayValues_usesBusinessLabelsWithoutMutatingQueryRows() {
        Map<String, Object> queryRow = new LinkedHashMap<>();
        queryRow.put("name", "Alice");
        queryRow.put("account_id", "01M0RAWPID");
        queryRow.put("account_id_display", "Acme Manufacturing");

        List<Map<String, Object>> result = DynamicDataServiceImpl.materializeReferenceDisplayValues(
                List.of(queryRow), Set.of("account_id"));

        assertEquals("Acme Manufacturing", result.getFirst().get("account_id"));
        assertEquals("01M0RAWPID", queryRow.get("account_id"));
    }

    @Test
    void materializeReferenceDisplayValues_doesNotFallBackToUnauthorizedPid() {
        Map<String, Object> queryRow = Map.of(
                "name", "Alice",
                "account_id", "01M0RAWPID");

        List<Map<String, Object>> result = DynamicDataServiceImpl.materializeReferenceDisplayValues(
                List.of(queryRow), Set.of("account_id"));

        assertNull(result.getFirst().get("account_id"));
        assertFalse(result.getFirst().containsValue("01M0RAWPID"));
    }
}
