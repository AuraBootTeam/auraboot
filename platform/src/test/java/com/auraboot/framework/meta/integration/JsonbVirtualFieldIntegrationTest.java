package com.auraboot.framework.meta.integration;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.util.JsonbFieldHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-level tests for JSONB virtual field handling.
 * Validates the full lifecycle: field definition -> pack (write) -> extract (read) -> merge (update).
 * Pure unit test — no Spring context needed.
 */
class JsonbVirtualFieldIntegrationTest {

    // ── Test model: CRM Activity with two virtual fields stored in "crm_act_ext" ──

    private static final ModelDefinition CRM_ACTIVITY = ModelDefinition.builder()
            .code("crm_activity")
            .tableName("mt_crm_activity")
            .fields(List.of(
                    FieldDefinition.builder()
                            .code("subject").dataType("string").columnName("subject").build(),
                    FieldDefinition.builder()
                            .code("crm_act_ext").dataType("json").columnName("crm_act_ext").build(),
                    FieldDefinition.builder()
                            .code("call_duration").dataType("integer").columnName("call_duration")
                            .jsonbColumn("crm_act_ext").jsonbPath("duration").build(),
                    FieldDefinition.builder()
                            .code("call_direction").dataType("string").columnName("call_direction")
                            .jsonbColumn("crm_act_ext").jsonbPath("direction").build()
            ))
            .build();

    // ── Model with two separate host columns ──

    private static final ModelDefinition MULTI_HOST = ModelDefinition.builder()
            .code("multi_host")
            .tableName("mt_multi_host")
            .fields(List.of(
                    FieldDefinition.builder()
                            .code("name").dataType("string").columnName("name").build(),
                    FieldDefinition.builder()
                            .code("ext_a").dataType("json").columnName("ext_a").build(),
                    FieldDefinition.builder()
                            .code("ext_b").dataType("jsonb").columnName("ext_b").build(),
                    FieldDefinition.builder()
                            .code("color").dataType("string").columnName("color")
                            .jsonbColumn("ext_a").jsonbPath("color").build(),
                    FieldDefinition.builder()
                            .code("weight").dataType("decimal").columnName("weight")
                            .jsonbColumn("ext_b").jsonbPath("weight").build()
            ))
            .build();

    @Nested
    @DisplayName("FieldDefinition — isJsonbVirtual and SQL expressions")
    class FieldDefinitionTests {

        @Test
        @DisplayName("isJsonbVirtual returns true when both jsonbColumn and jsonbPath are set")
        void isJsonbVirtual_trueWhenBothSet() {
            FieldDefinition field = FieldDefinition.builder()
                    .code("call_duration").dataType("integer").columnName("call_duration")
                    .jsonbColumn("crm_act_ext").jsonbPath("duration").build();

            assertTrue(field.isJsonbVirtual());
        }

        @Test
        @DisplayName("isJsonbVirtual returns false for regular fields")
        void isJsonbVirtual_falseForRegularField() {
            FieldDefinition field = FieldDefinition.builder()
                    .code("subject").dataType("string").columnName("subject").build();

            assertFalse(field.isJsonbVirtual());
            assertNull(field.getJsonbSelectExpression());
            assertNull(field.getJsonbFilterExpression());
        }

        @Test
        @DisplayName("isJsonbVirtual requires BOTH jsonbColumn and jsonbPath — partial is false")
        void isJsonbVirtual_partialConfigReturnsFalse() {
            assertFalse(FieldDefinition.builder()
                    .code("x").dataType("string").jsonbColumn("ext").build().isJsonbVirtual());
            assertFalse(FieldDefinition.builder()
                    .code("x").dataType("string").jsonbPath("key").build().isJsonbVirtual());
            assertFalse(FieldDefinition.builder()
                    .code("x").dataType("string").jsonbColumn("").jsonbPath("key").build().isJsonbVirtual());
            assertFalse(FieldDefinition.builder()
                    .code("x").dataType("string").jsonbColumn("ext").jsonbPath("").build().isJsonbVirtual());
        }

        @Test
        @DisplayName("SELECT expression uses correct PostgreSQL cast per data type")
        void selectExpression_correctCastPerType() {
            assertEquals("crm_act_ext->>'direction'",
                    buildVirtualField("string", "crm_act_ext", "direction").getJsonbSelectExpression());
            assertEquals("(crm_act_ext->>'duration')::integer",
                    buildVirtualField("integer", "crm_act_ext", "duration").getJsonbSelectExpression());
            assertEquals("(ext->>'amount')::numeric",
                    buildVirtualField("decimal", "ext", "amount").getJsonbSelectExpression());
            assertEquals("(ext->>'active')::boolean",
                    buildVirtualField("boolean", "ext", "active").getJsonbSelectExpression());
            assertEquals("(ext->>'created')::timestamp",
                    buildVirtualField("datetime", "ext", "created").getJsonbSelectExpression());
        }

        @Test
        @DisplayName("Filter expression matches select expression")
        void filterExpression_matchesSelect() {
            FieldDefinition field = buildVirtualField("integer", "ext", "count");
            assertEquals(field.getJsonbSelectExpression(), field.getJsonbFilterExpression());
        }
    }

    @Nested
    @DisplayName("Create — mergeJsonbFields packs virtual fields into host JSONB column")
    class CreateTests {

        @Test
        @DisplayName("Virtual fields are packed into host column, removed from top level")
        void packVirtualFieldsIntoHost() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("subject", "Follow up call");
            input.put("call_duration", 30);
            input.put("call_direction", "outbound");

            Map<String, Object> result = JsonbFieldHelper.mergeJsonbFields(CRM_ACTIVITY, input);

            // Regular field preserved
            assertEquals("Follow up call", result.get("subject"));
            // Virtual fields removed from top level
            assertFalse(result.containsKey("call_duration"));
            assertFalse(result.containsKey("call_direction"));
            // Packed into host column
            assertTrue(result.containsKey("crm_act_ext"));
            @SuppressWarnings("unchecked")
            Map<String, Object> ext = (Map<String, Object>) result.get("crm_act_ext");
            assertEquals(30, ext.get("duration"));
            assertEquals("outbound", ext.get("direction"));
        }

        @Test
        @DisplayName("Only regular fields — no JSONB manipulation")
        void noVirtualFields_passThrough() {
            Map<String, Object> input = new LinkedHashMap<>(Map.of("subject", "Test"));
            Map<String, Object> result = JsonbFieldHelper.mergeJsonbFields(CRM_ACTIVITY, input);

            assertEquals("Test", result.get("subject"));
            assertFalse(result.containsKey("crm_act_ext"));
        }
    }

    @Nested
    @DisplayName("Read — extracting virtual field values from JSONB data")
    class ReadTests {

        @Test
        @DisplayName("Host columns are correctly identified from model")
        void getJsonbHostColumns_identifiesHostColumns() {
            Set<String> hosts = JsonbFieldHelper.getJsonbHostColumns(CRM_ACTIVITY);
            assertEquals(1, hosts.size());
            assertTrue(hosts.contains("crm_act_ext"));
        }

        @Test
        @DisplayName("Multiple host columns (JSON and JSONB types) are detected")
        void getJsonbHostColumns_multipleHosts() {
            Set<String> hosts = JsonbFieldHelper.getJsonbHostColumns(MULTI_HOST);
            assertEquals(2, hosts.size());
            assertTrue(hosts.contains("ext_a"));
            assertTrue(hosts.contains("ext_b"));
        }

        @Test
        @DisplayName("toJsonString serializes map for DB storage")
        void toJsonString_serializesMap() {
            Map<String, Object> map = Map.of("duration", 30, "direction", "out");
            String json = JsonbFieldHelper.toJsonString(map);
            assertNotNull(json);
            assertTrue(json.contains("\"duration\""));
            assertTrue(json.contains("30"));
        }

        @Test
        @DisplayName("toJsonString passes through string values unchanged")
        void toJsonString_stringPassThrough() {
            assertEquals("{\"a\":1}", JsonbFieldHelper.toJsonString("{\"a\":1}"));
        }

        @Test
        @DisplayName("toJsonString returns null for null input")
        void toJsonString_nullReturnsNull() {
            assertNull(JsonbFieldHelper.toJsonString(null));
        }
    }

    @Nested
    @DisplayName("Update merge — updating one virtual field preserves others")
    class UpdateTests {

        @Test
        @DisplayName("Updating one virtual field preserves sibling in same host column")
        void updateOnePreservesOther() {
            Map<String, Object> updateData = new LinkedHashMap<>();
            updateData.put("call_duration", 45);

            Map<String, Object> existingRecord = new LinkedHashMap<>();
            existingRecord.put("subject", "Old subject");
            existingRecord.put("call_duration", "30");
            existingRecord.put("call_direction", "inbound");

            Map<String, Object> result = JsonbFieldHelper.mergeJsonbFieldsForUpdate(
                    CRM_ACTIVITY, updateData, existingRecord);

            @SuppressWarnings("unchecked")
            Map<String, Object> ext = (Map<String, Object>) result.get("crm_act_ext");
            assertNotNull(ext);
            assertEquals(45, ext.get("duration"));
            // call_direction was NOT in updateData but preserved from existing record
            assertEquals("inbound", ext.get("direction"));
        }

        @Test
        @DisplayName("Updating only regular fields does not touch JSONB column")
        void updateRegularFieldOnly_noJsonbChange() {
            Map<String, Object> updateData = new LinkedHashMap<>();
            updateData.put("subject", "New subject");

            Map<String, Object> existingRecord = new LinkedHashMap<>();
            existingRecord.put("call_duration", "30");

            Map<String, Object> result = JsonbFieldHelper.mergeJsonbFieldsForUpdate(
                    CRM_ACTIVITY, updateData, existingRecord);

            assertEquals("New subject", result.get("subject"));
            assertFalse(result.containsKey("crm_act_ext"));
        }

        @Test
        @DisplayName("Updating both virtual fields overwrites both in host column")
        void updateBothVirtualFields() {
            Map<String, Object> updateData = new LinkedHashMap<>();
            updateData.put("call_duration", 60);
            updateData.put("call_direction", "outbound");

            Map<String, Object> existingRecord = new LinkedHashMap<>();
            existingRecord.put("call_duration", "30");
            existingRecord.put("call_direction", "inbound");

            Map<String, Object> result = JsonbFieldHelper.mergeJsonbFieldsForUpdate(
                    CRM_ACTIVITY, updateData, existingRecord);

            @SuppressWarnings("unchecked")
            Map<String, Object> ext = (Map<String, Object>) result.get("crm_act_ext");
            assertEquals(60, ext.get("duration"));
            assertEquals("outbound", ext.get("direction"));
        }
    }

    @Nested
    @DisplayName("Multiple host columns — fields distributed across separate JSONB columns")
    class MultiHostTests {

        @Test
        @DisplayName("Virtual fields are packed into their respective host columns")
        void packIntoSeparateHosts() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("name", "Widget");
            input.put("color", "red");
            input.put("weight", 2.5);

            Map<String, Object> result = JsonbFieldHelper.mergeJsonbFields(MULTI_HOST, input);

            assertEquals("Widget", result.get("name"));
            assertFalse(result.containsKey("color"));
            assertFalse(result.containsKey("weight"));

            @SuppressWarnings("unchecked")
            Map<String, Object> extA = (Map<String, Object>) result.get("ext_a");
            assertEquals("red", extA.get("color"));

            @SuppressWarnings("unchecked")
            Map<String, Object> extB = (Map<String, Object>) result.get("ext_b");
            assertEquals(2.5, extB.get("weight"));
        }
    }

    @Nested
    @DisplayName("Null handling — edge cases")
    class NullHandlingTests {

        @Test
        @DisplayName("Virtual field with null value is preserved in host column")
        void nullVirtualFieldValue_preservedInHost() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("subject", "Test");
            input.put("call_duration", null);

            Map<String, Object> result = JsonbFieldHelper.mergeJsonbFields(CRM_ACTIVITY, input);

            @SuppressWarnings("unchecked")
            Map<String, Object> ext = (Map<String, Object>) result.get("crm_act_ext");
            assertNotNull(ext);
            assertTrue(ext.containsKey("duration"));
            assertNull(ext.get("duration"));
        }

        @Test
        @DisplayName("Merge with existing host column data preserves extra keys")
        void mergeWithExistingHostData() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("crm_act_ext", Map.of("custom_key", "preserved_value"));
            input.put("call_duration", 10);

            Map<String, Object> result = JsonbFieldHelper.mergeJsonbFields(CRM_ACTIVITY, input);

            @SuppressWarnings("unchecked")
            Map<String, Object> ext = (Map<String, Object>) result.get("crm_act_ext");
            assertEquals(10, ext.get("duration"));
            assertEquals("preserved_value", ext.get("custom_key"));
        }

        @Test
        @DisplayName("Empty data map returns empty result")
        void emptyDataMap() {
            Map<String, Object> result = JsonbFieldHelper.mergeJsonbFields(
                    CRM_ACTIVITY, new LinkedHashMap<>());
            assertTrue(result.isEmpty() || !result.containsKey("crm_act_ext"));
        }

        @Test
        @DisplayName("Model with no fields handles gracefully")
        void modelWithNoFields() {
            ModelDefinition emptyModel = ModelDefinition.builder()
                    .code("empty").tableName("mt_empty").build();

            Map<String, Object> input = new LinkedHashMap<>(Map.of("key", "value"));
            Map<String, Object> result = JsonbFieldHelper.mergeJsonbFields(emptyModel, input);
            assertEquals("value", result.get("key"));
        }

        @Test
        @DisplayName("Null existing record in update does not throw")
        void nullExistingRecord_noException() {
            Map<String, Object> updateData = new LinkedHashMap<>();
            updateData.put("call_duration", 15);

            Map<String, Object> result = JsonbFieldHelper.mergeJsonbFieldsForUpdate(
                    CRM_ACTIVITY, updateData, null);

            @SuppressWarnings("unchecked")
            Map<String, Object> ext = (Map<String, Object>) result.get("crm_act_ext");
            assertNotNull(ext);
            assertEquals(15, ext.get("duration"));
        }
    }

    // ── Full lifecycle: create -> read back -> update ──

    @Nested
    @DisplayName("Full lifecycle — create, read, update")
    class LifecycleTests {

        @Test
        @DisplayName("Create -> pack -> serialize -> update preserves data integrity")
        void fullCreateUpdateLifecycle() {
            // Step 1: Create — frontend sends flat fields
            Map<String, Object> createInput = new LinkedHashMap<>();
            createInput.put("subject", "Initial call");
            createInput.put("call_duration", 30);
            createInput.put("call_direction", "inbound");

            Map<String, Object> packed = JsonbFieldHelper.mergeJsonbFields(CRM_ACTIVITY, createInput);

            // Verify packed structure (what gets written to DB)
            assertEquals("Initial call", packed.get("subject"));
            @SuppressWarnings("unchecked")
            Map<String, Object> extAfterCreate = (Map<String, Object>) packed.get("crm_act_ext");
            assertEquals(30, extAfterCreate.get("duration"));
            assertEquals("inbound", extAfterCreate.get("direction"));

            // Step 2: Serialize to JSON string (simulating DB write/read)
            String jsonStr = JsonbFieldHelper.toJsonString(extAfterCreate);
            assertNotNull(jsonStr);
            assertTrue(jsonStr.contains("\"duration\""));

            // Step 3: Update — change only duration, direction should be preserved
            // Simulate the existing record as returned by DB (virtual fields extracted)
            Map<String, Object> existingRecord = new LinkedHashMap<>();
            existingRecord.put("subject", "Initial call");
            existingRecord.put("call_duration", "30");
            existingRecord.put("call_direction", "inbound");

            Map<String, Object> updateInput = new LinkedHashMap<>();
            updateInput.put("call_duration", 60);

            Map<String, Object> updated = JsonbFieldHelper.mergeJsonbFieldsForUpdate(
                    CRM_ACTIVITY, updateInput, existingRecord);

            @SuppressWarnings("unchecked")
            Map<String, Object> extAfterUpdate = (Map<String, Object>) updated.get("crm_act_ext");
            assertEquals(60, extAfterUpdate.get("duration"));
            assertEquals("inbound", extAfterUpdate.get("direction")); // preserved
        }
    }

    // ── Helper ──

    private static FieldDefinition buildVirtualField(String dataType, String jsonbColumn, String jsonbPath) {
        return FieldDefinition.builder()
                .code("test_" + jsonbPath)
                .dataType(dataType)
                .columnName("test_" + jsonbPath)
                .jsonbColumn(jsonbColumn)
                .jsonbPath(jsonbPath)
                .build();
    }
}
