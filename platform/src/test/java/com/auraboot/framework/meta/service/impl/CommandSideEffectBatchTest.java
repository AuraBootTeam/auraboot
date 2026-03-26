package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DocumentFlowService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BATCH_CREATE_RECORD and BATCH_UPDATE_RECORD sideEffect actions
 * in CommandSideEffectExecutor.
 */
@ExtendWith(MockitoExtension.class)
class CommandSideEffectBatchTest {

    @Mock
    private DynamicDataMapper dynamicDataMapper;
    @Mock
    private DynamicDataService dynamicDataService;
    @Mock
    private MetaModelService metaModelService;
    @Mock
    private CommandSpelEvaluator spelEvaluator;
    @Mock
    private DocumentFlowService documentFlowService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CommandSideEffectExecutor executor;

    private static final Long TENANT_ID = 1L;
    private static final Long USER_ID = 100L;

    @BeforeEach
    void setUp() {
        executor = new CommandSideEffectExecutor(dynamicDataMapper, dynamicDataService, metaModelService, spelEvaluator, documentFlowService, objectMapper);
    }

    // ── resolveItemFieldMapping unit tests ────────────────────────────────────

    @Nested
    @DisplayName("resolveItemFieldMapping — variable resolution")
    class ResolveItemFieldMappingTests {

        @Test
        @DisplayName("${item.xxx} resolves from the array item")
        void testItemReference() {
            Map<String, Object> fieldMapping = Map.of(
                    "product_code", "${item.product_code}",
                    "quantity", "${item.quantity}"
            );
            Map<String, Object> currentRecord = Map.of("id", "order-001");
            Map<String, Object> item = Map.of("product_code", "SKU-100", "quantity", 5);

            Map<String, Object> result = executor.resolveItemFieldMapping(fieldMapping, currentRecord, item);

            assertEquals("SKU-100", result.get("product_code"));
            assertEquals(5, result.get("quantity"));
        }

        @Test
        @DisplayName("${recordId} resolves to current record's id")
        void testRecordIdReference() {
            Map<String, Object> fieldMapping = Map.of(
                    "order_id", "${recordId}"
            );
            Map<String, Object> currentRecord = Map.of("id", "order-001");
            Map<String, Object> item = Map.of("product_code", "SKU-100");

            Map<String, Object> result = executor.resolveItemFieldMapping(fieldMapping, currentRecord, item);

            assertEquals("order-001", result.get("order_id"));
        }

        @Test
        @DisplayName("${fieldName} resolves from current record")
        void testCurrentRecordReference() {
            Map<String, Object> fieldMapping = Map.of(
                    "warehouse_code", "${warehouse_code}"
            );
            Map<String, Object> currentRecord = Map.of("id", "order-001", "warehouse_code", "WH-A");
            Map<String, Object> item = Map.of("product_code", "SKU-100");

            Map<String, Object> result = executor.resolveItemFieldMapping(fieldMapping, currentRecord, item);

            assertEquals("WH-A", result.get("warehouse_code"));
        }

        @Test
        @DisplayName("$current.fieldName legacy format resolves from current record")
        void testLegacyCurrentReference() {
            Map<String, Object> fieldMapping = Map.of(
                    "order_id", "$current.id"
            );
            Map<String, Object> currentRecord = Map.of("id", "order-001");
            Map<String, Object> item = Map.of("product_code", "SKU-100");

            Map<String, Object> result = executor.resolveItemFieldMapping(fieldMapping, currentRecord, item);

            assertEquals("order-001", result.get("order_id"));
        }

        @Test
        @DisplayName("Plain values pass through unchanged")
        void testPlainValues() {
            Map<String, Object> fieldMapping = Map.of(
                    "status", "pending",
                    "count", 42
            );
            Map<String, Object> currentRecord = Map.of("id", "order-001");
            Map<String, Object> item = Map.of("product_code", "SKU-100");

            Map<String, Object> result = executor.resolveItemFieldMapping(fieldMapping, currentRecord, item);

            assertEquals("pending", result.get("status"));
            assertEquals(42, result.get("count"));
        }

        @Test
        @DisplayName("Missing item field resolves to null")
        void testMissingItemField() {
            Map<String, Object> fieldMapping = Map.of(
                    "note", "${item.note}"
            );
            Map<String, Object> currentRecord = Map.of("id", "order-001");
            Map<String, Object> item = Map.of("product_code", "SKU-100");

            Map<String, Object> result = executor.resolveItemFieldMapping(fieldMapping, currentRecord, item);

            assertNull(result.get("note"));
        }

        @Test
        @DisplayName("Mixed references in a single mapping")
        void testMixedReferences() {
            Map<String, Object> fieldMapping = Map.of(
                    "order_id", "${recordId}",
                    "product_code", "${item.product_code}",
                    "warehouse", "${warehouse_code}",
                    "status", "new"
            );
            Map<String, Object> currentRecord = Map.of("id", "order-001", "warehouse_code", "WH-A");
            Map<String, Object> item = Map.of("product_code", "SKU-100");

            Map<String, Object> result = executor.resolveItemFieldMapping(fieldMapping, currentRecord, item);

            assertEquals("order-001", result.get("order_id"));
            assertEquals("SKU-100", result.get("product_code"));
            assertEquals("WH-A", result.get("warehouse"));
            assertEquals("new", result.get("status"));
        }

        @Test
        @DisplayName("Null item handled gracefully")
        void testNullItem() {
            Map<String, Object> fieldMapping = Map.of(
                    "product_code", "${item.product_code}",
                    "order_id", "${recordId}"
            );
            Map<String, Object> currentRecord = Map.of("id", "order-001");

            Map<String, Object> result = executor.resolveItemFieldMapping(fieldMapping, currentRecord, null);

            assertNull(result.get("product_code"));
            assertEquals("order-001", result.get("order_id"));
        }
    }

    // ── resolveSourceArray unit tests ─────────────────────────────────────────

    @Nested
    @DisplayName("resolveSourceArray — source field extraction")
    class ResolveSourceArrayTests {

        @Test
        @DisplayName("Extracts list of maps from currentRecord")
        void testExtractList() {
            List<Map<String, Object>> items = List.of(
                    Map.of("product_code", "SKU-1"),
                    Map.of("product_code", "SKU-2")
            );
            Map<String, Object> currentRecord = Map.of("lines", items);

            List<Map<String, Object>> result = executor.resolveSourceArray("lines", currentRecord);

            assertNotNull(result);
            assertEquals(2, result.size());
            assertEquals("SKU-1", result.get(0).get("product_code"));
        }

        @Test
        @DisplayName("Returns null for non-existent sourceField")
        void testMissingField() {
            Map<String, Object> currentRecord = Map.of("name", "test");

            List<Map<String, Object>> result = executor.resolveSourceArray("lines", currentRecord);

            assertNull(result);
        }

        @Test
        @DisplayName("Returns null for null currentRecord")
        void testNullRecord() {
            List<Map<String, Object>> result = executor.resolveSourceArray("lines", null);
            assertNull(result);
        }

        @Test
        @DisplayName("Returns null for non-list sourceField value")
        void testNonListValue() {
            Map<String, Object> currentRecord = Map.of("lines", "not-a-list");

            List<Map<String, Object>> result = executor.resolveSourceArray("lines", currentRecord);

            assertNull(result);
        }

        @Test
        @DisplayName("Skips non-map elements in the list")
        void testNonMapElements() {
            List<Object> items = List.of(
                    Map.of("product_code", "SKU-1"),
                    "invalid-element",
                    Map.of("product_code", "SKU-2")
            );
            Map<String, Object> currentRecord = Map.of("lines", items);

            List<Map<String, Object>> result = executor.resolveSourceArray("lines", currentRecord);

            assertNotNull(result);
            assertEquals(2, result.size());
        }
    }

    // ── BATCH_CREATE_RECORD tests ─────────────────────────────────────────────

    @Nested
    @DisplayName("BATCH_CREATE_RECORD — via executeSideEffectPhase pipeline")
    class BatchCreateTests {

        @Test
        @DisplayName("Creates records for each item in sourceField array")
        void testBasicBatchCreate() {
            List<Map<String, Object>> lines = List.of(
                    Map.of("product_code", "SKU-1", "quantity", 10),
                    Map.of("product_code", "SKU-2", "quantity", 20),
                    Map.of("product_code", "SKU-3", "quantity", 30)
            );
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", "order-001");
            payload.put("lines", lines);

            Map<String, Object> effect = new HashMap<>();
            effect.put("action", "batch_create_record");
            effect.put("targetModel", "order_line");
            effect.put("sourceField", "lines");
            effect.put("fieldMapping", Map.of(
                    "order_id", "${recordId}",
                    "product_code", "${item.product_code}",
                    "quantity", "${item.quantity}"
            ));

            when(dynamicDataService.create(anyString(), anyMap())).thenReturn(Map.of("id", "1"));

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
            executor.executeSideEffectPhase(execConfig, payload, TENANT_ID, USER_ID, null, null, null);

            // Verify 3 create calls
            verify(dynamicDataService, times(3)).create(eq("order_line"), anyMap());

            // Capture and verify each create call
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(dynamicDataService, times(3)).create(eq("order_line"), dataCaptor.capture());
            List<Map<String, Object>> captured = dataCaptor.getAllValues();

            // First item
            assertEquals("order-001", captured.get(0).get("order_id"));
            assertEquals("SKU-1", captured.get(0).get("product_code"));
            assertEquals(10, captured.get(0).get("quantity"));
            assertEquals(TENANT_ID, captured.get(0).get("tenant_id"));

            // Second item
            assertEquals("order-001", captured.get(1).get("order_id"));
            assertEquals("SKU-2", captured.get(1).get("product_code"));
            assertEquals(20, captured.get(1).get("quantity"));

            // Third item
            assertEquals("order-001", captured.get(2).get("order_id"));
            assertEquals("SKU-3", captured.get(2).get("product_code"));
            assertEquals(30, captured.get(2).get("quantity"));
        }

        @Test
        @DisplayName("BATCH_CREATE_RECORDS alias works identically")
        void testAliasAction() {
            List<Map<String, Object>> lines = List.of(
                    Map.of("product_code", "SKU-1")
            );
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", "order-001");
            payload.put("lines", lines);

            Map<String, Object> effect = new HashMap<>();
            effect.put("action", "batch_create_records");
            effect.put("targetModel", "order_line");
            effect.put("sourceField", "lines");
            effect.put("fieldMapping", Map.of("product_code", "${item.product_code}"));

            when(dynamicDataService.create(anyString(), anyMap())).thenReturn(Map.of("id", "1"));

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
            executor.executeSideEffectPhase(execConfig, payload, TENANT_ID, USER_ID, null, null, null);

            verify(dynamicDataService, times(1)).create(eq("order_line"), anyMap());
        }

        @Test
        @DisplayName("Empty sourceField array creates no records")
        void testEmptySourceField() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", "order-001");
            payload.put("lines", List.of());

            Map<String, Object> effect = new HashMap<>();
            effect.put("action", "batch_create_record");
            effect.put("targetModel", "order_line");
            effect.put("sourceField", "lines");
            effect.put("fieldMapping", Map.of("product_code", "${item.product_code}"));

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
            executor.executeSideEffectPhase(execConfig, payload, TENANT_ID, USER_ID, null, null, null);

            verify(dynamicDataService, never()).create(anyString(), anyMap());
        }

        @Test
        @DisplayName("Missing sourceField creates no records")
        void testMissingSourceField() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", "order-001");

            Map<String, Object> effect = new HashMap<>();
            effect.put("action", "batch_create_record");
            effect.put("targetModel", "order_line");
            effect.put("sourceField", "lines");
            effect.put("fieldMapping", Map.of("product_code", "${item.product_code}"));

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
            executor.executeSideEffectPhase(execConfig, payload, TENANT_ID, USER_ID, null, null, null);

            verify(dynamicDataService, never()).create(anyString(), anyMap());
        }

        @Test
        @DisplayName("Null sourceField config skips silently")
        void testNullSourceFieldConfig() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", "order-001");

            Map<String, Object> effect = new HashMap<>();
            effect.put("action", "batch_create_record");
            effect.put("targetModel", "order_line");
            // sourceField is intentionally not set
            effect.put("fieldMapping", Map.of("product_code", "${item.product_code}"));

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
            executor.executeSideEffectPhase(execConfig, payload, TENANT_ID, USER_ID, null, null, null);

            verify(dynamicDataService, never()).create(anyString(), anyMap());
        }

        @Test
        @DisplayName("Create failure throws BusinessException with index")
        void testCreateFailureThrows() {
            List<Map<String, Object>> lines = List.of(
                    Map.of("product_code", "SKU-1"),
                    Map.of("product_code", "SKU-2")
            );
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", "order-001");
            payload.put("lines", lines);

            Map<String, Object> effect = new HashMap<>();
            effect.put("action", "batch_create_record");
            effect.put("targetModel", "order_line");
            effect.put("sourceField", "lines");
            effect.put("fieldMapping", Map.of("product_code", "${item.product_code}"));

            // First succeeds, second fails
            when(dynamicDataService.create(eq("order_line"), anyMap()))
                    .thenReturn(Map.of("id", "1"))
                    .thenThrow(new RuntimeException("DB constraint violation"));

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));

            assertThrows(BusinessException.class, () ->
                    executor.executeSideEffectPhase(execConfig, payload, TENANT_ID, USER_ID, null, null, null));
        }

        @Test
        @DisplayName("Plain values in fieldMapping are preserved per item")
        void testPlainValuesInBatchCreate() {
            List<Map<String, Object>> lines = List.of(
                    Map.of("product_code", "SKU-1"),
                    Map.of("product_code", "SKU-2")
            );
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", "order-001");
            payload.put("lines", lines);

            Map<String, Object> effect = new HashMap<>();
            effect.put("action", "batch_create_record");
            effect.put("targetModel", "order_line");
            effect.put("sourceField", "lines");
            effect.put("fieldMapping", Map.of(
                    "product_code", "${item.product_code}",
                    "status", "pending"
            ));

            when(dynamicDataService.create(anyString(), anyMap())).thenReturn(Map.of("id", "1"));

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
            executor.executeSideEffectPhase(execConfig, payload, TENANT_ID, USER_ID, null, null, null);

            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(dynamicDataService, times(2)).create(eq("order_line"), dataCaptor.capture());

            for (Map<String, Object> data : dataCaptor.getAllValues()) {
                assertEquals("pending", data.get("status"));
            }
        }
    }

    // ── BATCH_UPDATE_RECORD tests ─────────────────────────────────────────────

    @Nested
    @DisplayName("BATCH_UPDATE_RECORD — via executeSideEffectPhase pipeline")
    class BatchUpdateTests {

        @Test
        @DisplayName("Updates each record identified by targetIdField in items")
        void testBasicBatchUpdate() {
            List<Map<String, Object>> items = List.of(
                    Map.of("inventory_id", "inv-001", "reserved_qty", 10),
                    Map.of("inventory_id", "inv-002", "reserved_qty", 20)
            );
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", "order-001");
            payload.put("items", items);

            Map<String, Object> effect = new HashMap<>();
            effect.put("action", "batch_update_record");
            effect.put("targetModel", "inventory");
            effect.put("sourceField", "items");
            effect.put("targetIdField", "inventory_id");
            effect.put("fieldMapping", Map.of("reserved_qty", "${item.reserved_qty}"));

            when(metaModelService.getTableName("inventory")).thenReturn("mt_inventory");
            when(dynamicDataMapper.update(anyString(), anyMap(), anyMap())).thenReturn(1);

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
            executor.executeSideEffectPhase(execConfig, payload, TENANT_ID, USER_ID, null, null, null);

            // Verify 2 update calls
            verify(dynamicDataMapper, times(2)).update(eq("mt_inventory"), anyMap(), anyMap());

            // Capture update data
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            ArgumentCaptor<Map<String, Object>> condCaptor = ArgumentCaptor.forClass(Map.class);
            verify(dynamicDataMapper, times(2)).update(eq("mt_inventory"),
                    dataCaptor.capture(), condCaptor.capture());

            List<Map<String, Object>> dataCalls = dataCaptor.getAllValues();
            List<Map<String, Object>> condCalls = condCaptor.getAllValues();

            // First item: reserved_qty=10, condition pid=inv-001
            assertEquals(10, dataCalls.get(0).get("reserved_qty"));
            assertEquals("inv-001", condCalls.get(0).get("pid"));

            // Second item: reserved_qty=20, condition pid=inv-002
            assertEquals(20, dataCalls.get(1).get("reserved_qty"));
            assertEquals("inv-002", condCalls.get(1).get("pid"));
        }

        @Test
        @DisplayName("BATCH_UPDATE_RECORDS alias works identically")
        void testAliasAction() {
            List<Map<String, Object>> items = List.of(
                    Map.of("inventory_id", "inv-001", "reserved_qty", 10)
            );
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", "order-001");
            payload.put("items", items);

            Map<String, Object> effect = new HashMap<>();
            effect.put("action", "batch_update_records");
            effect.put("targetModel", "inventory");
            effect.put("sourceField", "items");
            effect.put("targetIdField", "inventory_id");
            effect.put("fieldMapping", Map.of("reserved_qty", "${item.reserved_qty}"));

            when(metaModelService.getTableName("inventory")).thenReturn("mt_inventory");
            when(dynamicDataMapper.update(anyString(), anyMap(), anyMap())).thenReturn(1);

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
            executor.executeSideEffectPhase(execConfig, payload, TENANT_ID, USER_ID, null, null, null);

            verify(dynamicDataMapper, times(1)).update(eq("mt_inventory"), anyMap(), anyMap());
        }

        @Test
        @DisplayName("Numeric targetIdField uses id column lookup")
        void testNumericIdLookup() {
            List<Map<String, Object>> items = List.of(
                    Map.of("inventory_id", "12345", "reserved_qty", 10)
            );
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", "order-001");
            payload.put("items", items);

            Map<String, Object> effect = new HashMap<>();
            effect.put("action", "batch_update_record");
            effect.put("targetModel", "inventory");
            effect.put("sourceField", "items");
            effect.put("targetIdField", "inventory_id");
            effect.put("fieldMapping", Map.of("reserved_qty", "${item.reserved_qty}"));

            when(metaModelService.getTableName("inventory")).thenReturn("mt_inventory");
            when(dynamicDataMapper.update(anyString(), anyMap(), anyMap())).thenReturn(1);

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
            executor.executeSideEffectPhase(execConfig, payload, TENANT_ID, USER_ID, null, null, null);

            ArgumentCaptor<Map<String, Object>> condCaptor = ArgumentCaptor.forClass(Map.class);
            verify(dynamicDataMapper).update(anyString(), anyMap(), condCaptor.capture());

            // Numeric ID should use "id" column
            assertEquals(12345L, condCaptor.getValue().get("id"));
        }

        @Test
        @DisplayName("Empty sourceField array updates no records")
        void testEmptySourceField() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", "order-001");
            payload.put("items", List.of());

            Map<String, Object> effect = new HashMap<>();
            effect.put("action", "batch_update_record");
            effect.put("targetModel", "inventory");
            effect.put("sourceField", "items");
            effect.put("targetIdField", "inventory_id");
            effect.put("fieldMapping", Map.of("reserved_qty", "${item.reserved_qty}"));

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
            executor.executeSideEffectPhase(execConfig, payload, TENANT_ID, USER_ID, null, null, null);

            verify(dynamicDataMapper, never()).update(anyString(), anyMap(), anyMap());
        }

        @Test
        @DisplayName("Missing targetIdField config skips silently")
        void testMissingTargetIdField() {
            List<Map<String, Object>> items = List.of(
                    Map.of("reserved_qty", 10)
            );
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", "order-001");
            payload.put("items", items);

            Map<String, Object> effect = new HashMap<>();
            effect.put("action", "batch_update_record");
            effect.put("targetModel", "inventory");
            effect.put("sourceField", "items");
            // targetIdField is intentionally not set
            effect.put("fieldMapping", Map.of("reserved_qty", "${item.reserved_qty}"));

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
            executor.executeSideEffectPhase(execConfig, payload, TENANT_ID, USER_ID, null, null, null);

            verify(dynamicDataMapper, never()).update(anyString(), anyMap(), anyMap());
        }

        @Test
        @DisplayName("Item missing targetIdField value is skipped (continues to next)")
        void testItemMissingTargetId() {
            List<Map<String, Object>> items = List.of(
                    Map.of("reserved_qty", 10),  // No inventory_id
                    Map.of("inventory_id", "inv-002", "reserved_qty", 20)
            );
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", "order-001");
            payload.put("items", items);

            Map<String, Object> effect = new HashMap<>();
            effect.put("action", "batch_update_record");
            effect.put("targetModel", "inventory");
            effect.put("sourceField", "items");
            effect.put("targetIdField", "inventory_id");
            effect.put("fieldMapping", Map.of("reserved_qty", "${item.reserved_qty}"));

            when(metaModelService.getTableName("inventory")).thenReturn("mt_inventory");
            when(dynamicDataMapper.update(anyString(), anyMap(), anyMap())).thenReturn(1);

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
            executor.executeSideEffectPhase(execConfig, payload, TENANT_ID, USER_ID, null, null, null);

            // Only 1 update (second item), first item skipped
            verify(dynamicDataMapper, times(1)).update(eq("mt_inventory"), anyMap(), anyMap());
        }

        @Test
        @DisplayName("Update failure throws BusinessException with index")
        void testUpdateFailureThrows() {
            List<Map<String, Object>> items = List.of(
                    Map.of("inventory_id", "inv-001", "reserved_qty", 10),
                    Map.of("inventory_id", "inv-002", "reserved_qty", 20)
            );
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", "order-001");
            payload.put("items", items);

            Map<String, Object> effect = new HashMap<>();
            effect.put("action", "batch_update_record");
            effect.put("targetModel", "inventory");
            effect.put("sourceField", "items");
            effect.put("targetIdField", "inventory_id");
            effect.put("fieldMapping", Map.of("reserved_qty", "${item.reserved_qty}"));

            when(metaModelService.getTableName("inventory")).thenReturn("mt_inventory");
            when(dynamicDataMapper.update(anyString(), anyMap(), anyMap()))
                    .thenReturn(1)
                    .thenThrow(new RuntimeException("DB error"));

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));

            assertThrows(BusinessException.class, () ->
                    executor.executeSideEffectPhase(execConfig, payload, TENANT_ID, USER_ID, null, null, null));
        }

        @Test
        @DisplayName("Mixed ${item.xxx} and ${recordId} in fieldMapping")
        void testMixedReferencesInBatchUpdate() {
            List<Map<String, Object>> items = List.of(
                    Map.of("inventory_id", "inv-001", "reserved_qty", 10)
            );
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", "order-001");
            payload.put("items", items);

            Map<String, Object> effect = new HashMap<>();
            effect.put("action", "batch_update_record");
            effect.put("targetModel", "inventory");
            effect.put("sourceField", "items");
            effect.put("targetIdField", "inventory_id");
            effect.put("fieldMapping", Map.of(
                    "reserved_qty", "${item.reserved_qty}",
                    "last_order_id", "${recordId}",
                    "status", "reserved"
            ));

            when(metaModelService.getTableName("inventory")).thenReturn("mt_inventory");
            when(dynamicDataMapper.update(anyString(), anyMap(), anyMap())).thenReturn(1);

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
            executor.executeSideEffectPhase(execConfig, payload, TENANT_ID, USER_ID, null, null, null);

            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(dynamicDataMapper).update(anyString(), dataCaptor.capture(), anyMap());

            Map<String, Object> data = dataCaptor.getValue();
            assertEquals(10, data.get("reserved_qty"));
            assertEquals("order-001", data.get("last_order_id"));
            assertEquals("reserved", data.get("status"));
        }
    }

    // ── Nested format tests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Nested format (actions[]) — batch operations")
    class NestedFormatTests {

        @Test
        @DisplayName("BATCH_CREATE_RECORD works in nested actions[] format")
        void testNestedBatchCreate() {
            List<Map<String, Object>> lines = List.of(
                    Map.of("product_code", "SKU-1", "quantity", 5)
            );
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", "order-001");
            payload.put("lines", lines);

            Map<String, Object> actionDef = new HashMap<>();
            actionDef.put("type", "batch_create_record");
            actionDef.put("targetModel", "order_line");
            actionDef.put("sourceField", "lines");
            actionDef.put("fieldMapping", Map.of(
                    "order_id", "${recordId}",
                    "product_code", "${item.product_code}"
            ));

            Map<String, Object> effect = new HashMap<>();
            effect.put("actions", List.of(actionDef));

            when(dynamicDataService.create(anyString(), anyMap())).thenReturn(Map.of("id", "1"));

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
            executor.executeSideEffectPhase(execConfig, payload, TENANT_ID, USER_ID, null, null, null);

            verify(dynamicDataService, times(1)).create(eq("order_line"), anyMap());
        }

        @Test
        @DisplayName("BATCH_UPDATE_RECORD works in nested actions[] format")
        void testNestedBatchUpdate() {
            List<Map<String, Object>> items = List.of(
                    Map.of("inventory_id", "inv-001", "reserved_qty", 10)
            );
            Map<String, Object> payload = new HashMap<>();
            payload.put("id", "order-001");
            payload.put("items", items);

            Map<String, Object> actionDef = new HashMap<>();
            actionDef.put("action", "batch_update_record");
            actionDef.put("modelCode", "inventory");
            actionDef.put("sourceField", "items");
            actionDef.put("targetIdField", "inventory_id");
            actionDef.put("fields", Map.of("reserved_qty", "${item.reserved_qty}"));

            Map<String, Object> effect = new HashMap<>();
            effect.put("actions", List.of(actionDef));

            when(metaModelService.getTableName("inventory")).thenReturn("mt_inventory");
            when(dynamicDataMapper.update(anyString(), anyMap(), anyMap())).thenReturn(1);

            Map<String, Object> execConfig = Map.of("sideEffects", List.of(effect));
            executor.executeSideEffectPhase(execConfig, payload, TENANT_ID, USER_ID, null, null, null);

            verify(dynamicDataMapper, times(1)).update(eq("mt_inventory"), anyMap(), anyMap());
        }
    }

    // ── Direct method tests ───────────────────────────────────────────────────

    @Nested
    @DisplayName("executeBatchCreate — direct method tests")
    class DirectBatchCreateTests {

        @Test
        @DisplayName("Sets tenant_id on each created record")
        void testTenantIdSetOnEachRecord() {
            Map<String, Object> currentRecord = new HashMap<>();
            currentRecord.put("id", "order-001");
            currentRecord.put("lines", List.of(
                    Map.of("product_code", "SKU-1"),
                    Map.of("product_code", "SKU-2")
            ));

            Map<String, Object> fieldMapping = Map.of("product_code", "${item.product_code}");

            when(dynamicDataService.create(anyString(), anyMap())).thenReturn(Map.of("id", "1"));

            executor.executeBatchCreate("order_line", "lines", fieldMapping, currentRecord, TENANT_ID, USER_ID);

            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(dynamicDataService, times(2)).create(eq("order_line"), dataCaptor.capture());

            for (Map<String, Object> data : dataCaptor.getAllValues()) {
                assertEquals(TENANT_ID, data.get("tenant_id"));
            }
        }
    }

    @Nested
    @DisplayName("executeBatchUpdate — direct method tests")
    class DirectBatchUpdateTests {

        @Test
        @DisplayName("No-record-found warning does not throw (continues)")
        void testNoRecordFoundContinues() {
            Map<String, Object> currentRecord = new HashMap<>();
            currentRecord.put("id", "order-001");
            currentRecord.put("items", List.of(
                    Map.of("inventory_id", "inv-001", "reserved_qty", 10),
                    Map.of("inventory_id", "inv-002", "reserved_qty", 20)
            ));

            Map<String, Object> fieldMapping = Map.of("reserved_qty", "${item.reserved_qty}");

            when(metaModelService.getTableName("inventory")).thenReturn("mt_inventory");
            // First returns 0 (not found), second returns 1
            when(dynamicDataMapper.update(anyString(), anyMap(), anyMap()))
                    .thenReturn(0)
                    .thenReturn(1);

            executor.executeBatchUpdate("inventory", "items", "inventory_id",
                    fieldMapping, currentRecord, TENANT_ID);

            // Both updates should be attempted
            verify(dynamicDataMapper, times(2)).update(eq("mt_inventory"), anyMap(), anyMap());
        }
    }
}
