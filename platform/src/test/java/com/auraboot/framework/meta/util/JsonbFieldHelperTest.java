package com.auraboot.framework.meta.util;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonbFieldHelperTest {

    @Test
    void mergeJsonbFields_mergesVirtualFieldsIntoHostColumn() {
        ModelDefinition model = buildTestModel();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subject", "Follow up call");
        data.put("call_duration", 30);
        data.put("call_direction", "outbound");

        Map<String, Object> result = JsonbFieldHelper.mergeJsonbFields(model, data);

        assertEquals("Follow up call", result.get("subject"));
        assertFalse(result.containsKey("call_duration"));
        assertFalse(result.containsKey("call_direction"));
        assertTrue(result.containsKey("ext"));

        @SuppressWarnings("unchecked")
        Map<String, Object> ext = (Map<String, Object>) result.get("ext");
        assertEquals(30, ext.get("duration"));
        assertEquals("outbound", ext.get("direction"));
    }

    @Test
    void mergeJsonbFields_mergesWithExistingJsonbData() {
        ModelDefinition model = buildTestModel();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ext", Map.of("existing_key", "preserved"));
        data.put("call_duration", 45);

        Map<String, Object> result = JsonbFieldHelper.mergeJsonbFields(model, data);

        @SuppressWarnings("unchecked")
        Map<String, Object> ext = (Map<String, Object>) result.get("ext");
        assertEquals(45, ext.get("duration"));
        assertEquals("preserved", ext.get("existing_key"));
    }

    @Test
    void mergeJsonbFields_handlesNullValues() {
        ModelDefinition model = buildTestModel();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subject", "Test");
        data.put("call_duration", null);

        Map<String, Object> result = JsonbFieldHelper.mergeJsonbFields(model, data);

        @SuppressWarnings("unchecked")
        Map<String, Object> ext = (Map<String, Object>) result.get("ext");
        assertTrue(ext.containsKey("duration"));
        assertNull(ext.get("duration"));
    }

    @Test
    void mergeJsonbFields_noJsonbFields_unchanged() {
        ModelDefinition model = ModelDefinition.builder()
                .code("simple")
                .tableName("mt_simple")
                .fields(List.of(
                        FieldDefinition.builder().code("name").dataType("string").columnName("name").build()
                ))
                .build();

        Map<String, Object> data = new LinkedHashMap<>(Map.of("name", "test"));
        Map<String, Object> result = JsonbFieldHelper.mergeJsonbFields(model, data);
        assertEquals("test", result.get("name"));
        assertEquals(1, result.size());
    }

    @Test
    void mergeJsonbFieldsForUpdate_preservesUnmodifiedKeys() {
        ModelDefinition model = buildTestModel();

        // Update only call_duration, leave call_direction alone
        Map<String, Object> updateData = new LinkedHashMap<>();
        updateData.put("call_duration", 45);

        // Existing record has both JSONB virtual fields
        Map<String, Object> existingRecord = new LinkedHashMap<>();
        existingRecord.put("subject", "Old subject");
        existingRecord.put("call_duration", "30");
        existingRecord.put("call_direction", "inbound");

        Map<String, Object> result = JsonbFieldHelper.mergeJsonbFieldsForUpdate(model, updateData, existingRecord);

        @SuppressWarnings("unchecked")
        Map<String, Object> ext = (Map<String, Object>) result.get("ext");
        assertNotNull(ext);
        assertEquals(45, ext.get("duration"));
        // call_direction was NOT in updateData, but should be preserved from existing
        assertEquals("inbound", ext.get("direction"));
    }

    @Test
    void mergeJsonbFieldsForUpdate_noVirtualFieldsInUpdate_returnsAsIs() {
        ModelDefinition model = buildTestModel();

        Map<String, Object> updateData = new LinkedHashMap<>();
        updateData.put("subject", "New subject");

        Map<String, Object> existingRecord = new LinkedHashMap<>();
        existingRecord.put("call_duration", "30");

        Map<String, Object> result = JsonbFieldHelper.mergeJsonbFieldsForUpdate(model, updateData, existingRecord);
        assertEquals("New subject", result.get("subject"));
        assertFalse(result.containsKey("ext")); // no JSONB fields touched
    }

    @Test
    void getJsonbHostColumns_returnsOnlyHostColumns() {
        ModelDefinition model = buildTestModel();
        var hostColumns = JsonbFieldHelper.getJsonbHostColumns(model);
        assertEquals(1, hostColumns.size());
        assertTrue(hostColumns.contains("ext"));
    }

    @Test
    void getJsonbHostColumns_matchesJsonAndJsonb() {
        // Test with "json" dataType (as stored in DB)
        ModelDefinition model = ModelDefinition.builder()
                .code("test")
                .tableName("mt_test")
                .fields(List.of(
                        FieldDefinition.builder().code("ext").dataType("json").columnName("ext").build(),
                        FieldDefinition.builder().code("meta").dataType("jsonb").columnName("meta").build()
                ))
                .build();
        var hostColumns = JsonbFieldHelper.getJsonbHostColumns(model);
        assertEquals(2, hostColumns.size());
        assertTrue(hostColumns.contains("ext"));
        assertTrue(hostColumns.contains("meta"));
    }

    @Test
    void toJsonString_serializesMap() {
        Map<String, Object> map = Map.of("duration", 30, "direction", "out");
        String json = JsonbFieldHelper.toJsonString(map);
        assertNotNull(json);
        assertTrue(json.contains("\"duration\""));
        assertTrue(json.contains("30"));
    }

    @Test
    void toJsonString_serializesJavaTimeValuesAsIsoStrings() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("dueDate", LocalDate.parse("2026-05-15"));
        map.put("startTime", Instant.parse("2026-05-15T02:00:00Z"));

        String json = JsonbFieldHelper.toJsonString(map);

        assertTrue(json.contains("\"dueDate\":\"2026-05-15\""));
        assertTrue(json.contains("\"startTime\":\"2026-05-15T02:00:00Z\""));
    }

    @Test
    void shouldSerializeJsonValue_recognizesCollections() {
        assertTrue(JsonbFieldHelper.shouldSerializeJsonValue(List.of("a", "b")));
        assertTrue(JsonbFieldHelper.shouldSerializeJsonValue(Map.of("a", 1)));
        assertFalse(JsonbFieldHelper.shouldSerializeJsonValue("{\"a\":1}"));
    }

    @Test
    void toJsonString_passesStringThrough() {
        assertEquals("{\"a\":1}", JsonbFieldHelper.toJsonString("{\"a\":1}"));
    }

    @Test
    void toJsonString_returnsNullForNull() {
        assertNull(JsonbFieldHelper.toJsonString(null));
    }

    private ModelDefinition buildTestModel() {
        return ModelDefinition.builder()
                .code("crm_activity")
                .tableName("mt_crm_activity")
                .fields(List.of(
                        FieldDefinition.builder()
                                .code("subject").dataType("string").columnName("subject").build(),
                        FieldDefinition.builder()
                                .code("ext").dataType("json").columnName("ext").build(),
                        FieldDefinition.builder()
                                .code("call_duration").dataType("integer").columnName("call_duration")
                                .jsonbColumn("ext").jsonbPath("duration").build(),
                        FieldDefinition.builder()
                                .code("call_direction").dataType("string").columnName("call_direction")
                                .jsonbColumn("ext").jsonbPath("direction").build()
                ))
                .build();
    }
}
