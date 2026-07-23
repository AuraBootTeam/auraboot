package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.impl.CriticalPathService.CriticalPathResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CriticalPathService CPM algorithm.
 */
@ExtendWith(MockitoExtension.class)
class CriticalPathServiceTest {

    @Mock
    private DynamicDataMapper dynamicDataMapper;

    private CriticalPathService service;

    @BeforeEach
    void setUp() {
        service = new CriticalPathService(dynamicDataMapper);
    }

    private Map<String, Object> node(String id, int duration, String deps) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("duration_days", duration);
        row.put("dependencies", deps);
        return row;
    }

    @Test
    @DisplayName("Empty project returns empty result")
    void emptyProject() {
        when(dynamicDataMapper.queryList(anyString(), isNull(), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(Collections.emptyList());

        CriticalPathResult result = service.compute("test_table", "project_id", "p1", "duration_days", "dependencies");
        assertNotNull(result);
        assertTrue(result.criticalPathNodeIds().isEmpty());
        assertEquals(0, result.totalDuration());
        assertNull(result.error());
    }

    @Test
    @DisplayName("Single task is always critical path")
    void singleTask() {
        when(dynamicDataMapper.queryList(anyString(), isNull(), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(List.of(node("A", 5, null)));

        CriticalPathResult result = service.compute("test_table", "project_id", "p1", "duration_days", "dependencies");
        assertEquals(5, result.totalDuration());
        assertEquals(List.of("A"), result.criticalPathNodeIds());
        assertNull(result.error());
    }

    @Test
    @DisplayName("Linear chain: A→B→C, all on critical path")
    void linearChain() {
        // A(3) → B(2) → C(4)
        List<Map<String, Object>> nodes = List.of(
                node("A", 3, null),
                node("B", 2, "A"),
                node("C", 4, "B")
        );
        when(dynamicDataMapper.queryList(anyString(), isNull(), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(nodes);

        CriticalPathResult result = service.compute("test_table", "project_id", "p1", "duration_days", "dependencies");
        assertEquals(9, result.totalDuration()); // 3+2+4
        assertEquals(List.of("A", "B", "C"), result.criticalPathNodeIds());

        // Verify schedule for B
        Map<String, Integer> bSchedule = result.scheduleMap().get("B");
        assertEquals(3, bSchedule.get("es"));  // starts after A finishes
        assertEquals(5, bSchedule.get("ef"));  // 3+2
        assertEquals(0, bSchedule.get("slack"));
    }

    @Test
    @DisplayName("Diamond: A→B,C→D — identifies longer path as critical")
    void diamondGraph() {
        // A(2) → B(5), A(2) → C(3), B → D(1), C → D(1)
        // Critical path: A→B→D = 2+5+1 = 8
        // Non-critical: A→C→D = 2+3+1 = 6 (slack=2)
        List<Map<String, Object>> nodes = List.of(
                node("A", 2, null),
                node("B", 5, "A"),
                node("C", 3, "A"),
                node("D", 1, "B,C")
        );
        when(dynamicDataMapper.queryList(anyString(), isNull(), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(nodes);

        CriticalPathResult result = service.compute("test_table", "project_id", "p1", "duration_days", "dependencies");
        assertEquals(8, result.totalDuration());
        assertTrue(result.criticalPathNodeIds().contains("A"));
        assertTrue(result.criticalPathNodeIds().contains("B"));
        assertTrue(result.criticalPathNodeIds().contains("D"));
        assertFalse(result.criticalPathNodeIds().contains("C"));

        // C should have slack of 2
        assertEquals(2, result.scheduleMap().get("C").get("slack"));
    }

    @Test
    @DisplayName("Parallel independent tasks — both are critical if same duration")
    void parallelTasks() {
        // A(5) and B(5), no dependencies
        List<Map<String, Object>> nodes = List.of(
                node("A", 5, null),
                node("B", 5, null)
        );
        when(dynamicDataMapper.queryList(anyString(), isNull(), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(nodes);

        CriticalPathResult result = service.compute("test_table", "project_id", "p1", "duration_days", "dependencies");
        assertEquals(5, result.totalDuration());
        // Both are critical since they both have slack=0 (both are 'end' nodes with max EF)
        assertEquals(2, result.criticalPathNodeIds().size());
    }

    @Test
    @DisplayName("Cycle detected returns error")
    void cycleDetection() {
        // A → B → C → A (cycle)
        List<Map<String, Object>> nodes = List.of(
                node("A", 2, "C"),
                node("B", 3, "A"),
                node("C", 1, "B")
        );
        when(dynamicDataMapper.queryList(anyString(), isNull(), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(nodes);

        CriticalPathResult result = service.compute("test_table", "project_id", "p1", "duration_days", "dependencies");
        assertNotNull(result.error());
        assertTrue(result.error().contains("Cycle"));
    }

    @Test
    @DisplayName("Dependencies referencing non-existent nodes are ignored")
    void missingDependency() {
        // B depends on "X" (doesn't exist) and "A"
        List<Map<String, Object>> nodes = List.of(
                node("A", 3, null),
                node("B", 2, "X,A")
        );
        when(dynamicDataMapper.queryList(anyString(), isNull(), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(nodes);

        CriticalPathResult result = service.compute("test_table", "project_id", "p1", "duration_days", "dependencies");
        assertNull(result.error());
        assertEquals(5, result.totalDuration()); // A(3) + B(2)
    }

    @Test
    @DisplayName("Schedule map includes ES/EF/LS/LF/slack for all nodes")
    void scheduleMapCompleteness() {
        List<Map<String, Object>> nodes = List.of(
                node("A", 3, null),
                node("B", 2, "A")
        );
        when(dynamicDataMapper.queryList(anyString(), isNull(), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(nodes);

        CriticalPathResult result = service.compute("test_table", "project_id", "p1", "duration_days", "dependencies");

        Map<String, Integer> aSchedule = result.scheduleMap().get("A");
        assertNotNull(aSchedule);
        assertEquals(0, aSchedule.get("es"));
        assertEquals(3, aSchedule.get("ef"));
        assertEquals(0, aSchedule.get("ls"));
        assertEquals(3, aSchedule.get("lf"));
        assertEquals(0, aSchedule.get("slack"));
        assertEquals(3, aSchedule.get("duration"));
    }

    @Test
    @DisplayName("Null nodes list returns empty result")
    void nullNodes() {
        when(dynamicDataMapper.queryList(anyString(), isNull(), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(null);

        CriticalPathResult result = service.compute("test_table", "project_id", "p1", "duration_days", "dependencies");
        assertNotNull(result);
        assertTrue(result.criticalPathNodeIds().isEmpty());
    }

    @Test
    @DisplayName("SEC-20260723-03: malicious projectIdField is rejected before any SQL runs")
    void rejectsInjectedProjectIdField() {
        // projectIdField comes from @RequestParam and is concatenated as a column identifier.
        // A boolean-injection payload must be rejected by the identifier whitelist, and the
        // mapper must never be reached.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.compute("mt_pm_wbs_node", "1=1 OR pm_wbs_project_id", "p1",
                        "pm_wbs_duration_days", "pm_wbs_dependencies"));
        assertTrue(ex.getMessage().contains("Invalid SQL identifier"));
        verifyNoInteractions(dynamicDataMapper);
    }

    @Test
    @DisplayName("SEC-20260723-03: legitimate snake_case field identifiers pass validation")
    void acceptsValidFieldIdentifiers() {
        when(dynamicDataMapper.queryList(anyString(), isNull(), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(Collections.emptyList());
        CriticalPathResult result = service.compute("mt_pm_wbs_node", "pm_wbs_project_id", "p1",
                "pm_wbs_duration_days", "pm_wbs_dependencies");
        assertNotNull(result);
    }
}
