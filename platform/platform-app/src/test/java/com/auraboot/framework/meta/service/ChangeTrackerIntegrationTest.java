package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.ChangeLogQueryRequest;
import com.auraboot.framework.meta.dto.ChangeRecord;
import com.auraboot.framework.meta.dto.FieldChange;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.entity.DataChangeLog;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ChangeTracker and ChangeLogService.
 *
 * @since 5.1.0
 */
@DisplayName("P5-3: Change Tracker Integration Tests")
class ChangeTrackerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ChangeTracker changeTracker;

    @Autowired
    private ChangeLogService changeLogService;

    @BeforeEach
    void setUp() {
        super.setupTenantContext();
    }

    // ==================== Diff Tests ====================

    @Test
    @DisplayName("Diff: CREATE operation (before=null)")
    void testDiffCreate() {
        Map<String, Object> after = Map.of(
                "name", "New Product",
                "price", 99.99,
                "status", "active"
        );

        List<FieldChange> changes = changeTracker.diff(null, after, "product");

        assertEquals(3, changes.size());
        changes.forEach(c -> {
            assertNull(c.getOldValue());
            assertNotNull(c.getNewValue());
        });
    }

    @Test
    @DisplayName("Diff: DELETE operation (after=null)")
    void testDiffDelete() {
        Map<String, Object> before = Map.of(
                "name", "Old Product",
                "price", 50.0
        );

        List<FieldChange> changes = changeTracker.diff(before, null, "product");

        assertEquals(2, changes.size());
        changes.forEach(c -> {
            assertNotNull(c.getOldValue());
            assertNull(c.getNewValue());
        });
    }

    @Test
    @DisplayName("Diff: UPDATE detects changed fields only")
    void testDiffUpdate() {
        Map<String, Object> before = new HashMap<>();
        before.put("name", "Product A");
        before.put("price", 100);
        before.put("status", "active");

        Map<String, Object> after = new HashMap<>();
        after.put("name", "Product A");      // unchanged
        after.put("price", 120);             // changed
        after.put("status", "inactive");     // changed

        List<FieldChange> changes = changeTracker.diff(before, after, "product");

        assertEquals(2, changes.size());
        assertTrue(changes.stream().anyMatch(c -> "price".equals(c.getFieldCode())));
        assertTrue(changes.stream().anyMatch(c -> "status".equals(c.getFieldCode())));
    }

    @Test
    @DisplayName("Diff: ignores system fields (id, tenant_id, created_at, etc.)")
    void testDiffIgnoresSystemFields() {
        Map<String, Object> before = new HashMap<>();
        before.put("id", 1L);
        before.put("tenant_id", 100L);
        before.put("created_at", "2024-01-01");
        before.put("name", "Old");

        Map<String, Object> after = new HashMap<>();
        after.put("id", 1L);
        after.put("tenant_id", 100L);
        after.put("created_at", "2024-01-02");
        after.put("name", "New");

        List<FieldChange> changes = changeTracker.diff(before, after, "product");

        assertEquals(1, changes.size());
        assertEquals("name", changes.get(0).getFieldCode());
    }

    @Test
    @DisplayName("Diff: detects new fields added in update")
    void testDiffNewFieldAdded() {
        Map<String, Object> before = new HashMap<>();
        before.put("name", "Product");

        Map<String, Object> after = new HashMap<>();
        after.put("name", "Product");
        after.put("description", "New description");

        List<FieldChange> changes = changeTracker.diff(before, after, "product");

        assertEquals(1, changes.size());
        assertEquals("description", changes.get(0).getFieldCode());
        assertNull(changes.get(0).getOldValue());
        assertEquals("New description", changes.get(0).getNewValue());
    }

    @Test
    @DisplayName("Diff: both null returns empty list")
    void testDiffBothNull() {
        List<FieldChange> changes = changeTracker.diff(null, null, "product");
        assertTrue(changes.isEmpty());
    }

    @Test
    @DisplayName("Diff: no changes returns empty list")
    void testDiffNoChanges() {
        Map<String, Object> data = Map.of("name", "Same", "price", 100);
        List<FieldChange> changes = changeTracker.diff(data, new HashMap<>(data), "product");
        assertTrue(changes.isEmpty());
    }

    // ==================== Record Change Tests ====================

    @Test
    @DisplayName("Record change: CREATE operation persists to DB")
    void testRecordChangeCreate() {
        ChangeRecord record = ChangeRecord.builder()
                .modelCode("order")
                .recordId("ORD-001")
                .operation("create")
                .changedBy(MetaContext.getCurrentUserId())
                .changes(List.of(
                        FieldChange.builder().fieldCode("amount").oldValue(null).newValue(500).build()
                ))
                .snapshotAfter(Map.of("amount", 500, "status", "new"))
                .build();

        assertDoesNotThrow(() -> changeTracker.recordChange(record));

        List<DataChangeLog> history = changeLogService.getHistory("order", "ORD-001");
        assertFalse(history.isEmpty());
        assertEquals("create", history.get(0).getOperation());
    }

    @Test
    @DisplayName("Record change: UPDATE operation with snapshots")
    void testRecordChangeUpdate() {
        String recordId = "ORD-" + System.currentTimeMillis();
        ChangeRecord record = ChangeRecord.builder()
                .modelCode("order")
                .recordId(recordId)
                .operation("update")
                .changedBy(MetaContext.getCurrentUserId())
                .commandCode("updateOrderStatus")
                .changes(List.of(
                        FieldChange.builder().fieldCode("status").oldValue("new").newValue("paid").build()
                ))
                .snapshotBefore(Map.of("status", "new", "amount", 500))
                .snapshotAfter(Map.of("status", "paid", "amount", 500))
                .build();

        changeTracker.recordChange(record);

        List<DataChangeLog> history = changeLogService.getHistory("order", recordId);
        assertEquals(1, history.size());
        DataChangeLog log = history.get(0);
        assertEquals("update", log.getOperation());
        assertEquals("updateOrderStatus", log.getCommandCode());
        assertNotNull(log.getChanges());
        assertNotNull(log.getSnapshotBefore());
        assertNotNull(log.getSnapshotAfter());
    }

    @Test
    @DisplayName("Record change: DELETE operation")
    void testRecordChangeDelete() {
        String recordId = "ORD-DEL-" + System.currentTimeMillis();
        ChangeRecord record = ChangeRecord.builder()
                .modelCode("order")
                .recordId(recordId)
                .operation("delete")
                .changedBy(MetaContext.getCurrentUserId())
                .changes(List.of(
                        FieldChange.builder().fieldCode("amount").oldValue(500).newValue(null).build()
                ))
                .snapshotBefore(Map.of("amount", 500))
                .build();

        changeTracker.recordChange(record);

        List<DataChangeLog> history = changeLogService.getHistory("order", recordId);
        assertEquals(1, history.size());
        assertEquals("delete", history.get(0).getOperation());
    }

    // ==================== ChangeLogService Tests ====================

    @Test
    @DisplayName("ChangeLogService: getHistory returns chronological order")
    void testGetHistoryOrder() {
        String recordId = "HIST-" + System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            changeTracker.recordChange(ChangeRecord.builder()
                    .modelCode("product")
                    .recordId(recordId)
                    .operation("update")
                    .changedBy(MetaContext.getCurrentUserId())
                    .changes(List.of(FieldChange.builder().fieldCode("v").oldValue(i).newValue(i + 1).build()))
                    .build());
        }

        List<DataChangeLog> history = changeLogService.getHistory("product", recordId);
        assertEquals(3, history.size());
    }

    @Test
    @DisplayName("ChangeLogService: getByUser with pagination")
    void testGetByUser() {
        Long userId = MetaContext.getCurrentUserId();
        for (int i = 0; i < 5; i++) {
            changeTracker.recordChange(ChangeRecord.builder()
                    .modelCode("item")
                    .recordId("ITEM-" + i + "-" + System.currentTimeMillis())
                    .operation("create")
                    .changedBy(userId)
                    .changes(List.of())
                    .build());
        }

        ChangeLogQueryRequest request = new ChangeLogQueryRequest();
        request.setPageNum(1);
        request.setPageSize(3);
        PaginationResult<DataChangeLog> result = changeLogService.getByUser(userId, request);

        assertNotNull(result);
        assertNotNull(result.getRecords());
        assertTrue(result.getTotal() >= 5);
        assertTrue(result.getRecords().size() <= 3);
    }

    @Test
    @DisplayName("ChangeLogService: getById returns specific entry")
    void testGetById() {
        String recordId = "SINGLE-" + System.currentTimeMillis();
        changeTracker.recordChange(ChangeRecord.builder()
                .modelCode("widget")
                .recordId(recordId)
                .operation("create")
                .changedBy(MetaContext.getCurrentUserId())
                .changes(List.of())
                .build());

        List<DataChangeLog> history = changeLogService.getHistory("widget", recordId);
        assertFalse(history.isEmpty());

        DataChangeLog found = changeLogService.getById(history.get(0).getId());
        assertNotNull(found);
        assertEquals(recordId, found.getRecordId());
    }

    @Test
    @DisplayName("ChangeLogService: getHistory for nonexistent record returns empty")
    void testGetHistoryEmpty() {
        List<DataChangeLog> history = changeLogService.getHistory("nonexistent", "no-such-id");
        assertTrue(history.isEmpty());
    }
}
