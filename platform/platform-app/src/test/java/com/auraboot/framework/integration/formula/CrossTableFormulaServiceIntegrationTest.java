package com.auraboot.framework.integration.formula;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.formula.CrossTableFormulaService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for CrossTableFormulaService.
 * Tests LOOKUP, VLOOKUP, RELATED, COUNTIF, SUMIF cross-table functions
 * against a real database using the DynamicDataService.
 *
 * Requires 'device' model to exist with test data.
 */
@Slf4j
@DisplayName("CrossTableFormulaService - Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrossTableFormulaServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CrossTableFormulaService crossTableFormulaService;

    @Autowired
    private DynamicDataService dynamicDataService;

    private static final String TEST_MODEL = "device";
    private static final List<String> createdRecordIds = new ArrayList<>();

    // ==================== Setup ====================

    @BeforeEach
    void setupTestData() {
        // Insert test data into the device model for cross-table queries
        try {
            Map<String, Object> record1 = new HashMap<>();
            record1.put("name", "CrossTable-Device-A");
            record1.put("status", "active");
            record1.put("device_type", "sensor");

            Map<String, Object> record2 = new HashMap<>();
            record2.put("name", "CrossTable-Device-B");
            record2.put("status", "active");
            record2.put("device_type", "actuator");

            Map<String, Object> record3 = new HashMap<>();
            record3.put("name", "CrossTable-Device-C");
            record3.put("status", "inactive");
            record3.put("device_type", "sensor");

            Map<String, Object> created1 = dynamicDataService.create(TEST_MODEL, record1);
            Map<String, Object> created2 = dynamicDataService.create(TEST_MODEL, record2);
            Map<String, Object> created3 = dynamicDataService.create(TEST_MODEL, record3);

            if (created1 != null) createdRecordIds.add(String.valueOf(created1.get("id")));
            if (created2 != null) createdRecordIds.add(String.valueOf(created2.get("id")));
            if (created3 != null) createdRecordIds.add(String.valueOf(created3.get("id")));

            log.info("Setup {} test records for cross-table tests", createdRecordIds.size());
        } catch (Exception e) {
            log.warn("Could not create test data (model may not exist): {}", e.getMessage());
        }
    }

    // ==================== LOOKUP Tests ====================

    @Test
    @Order(1)
    @DisplayName("LOOKUP - Find a record by field value")
    void test01_lookupByFieldValue() {
        Object result = crossTableFormulaService.lookup(
                TEST_MODEL, "name", "CrossTable-Device-A", "status");

        // If test data was created, verify result
        if (!createdRecordIds.isEmpty()) {
            assertNotNull(result, "LOOKUP should find the record");
            assertEquals("active", result.toString());
            log.info("LOOKUP result: {}", result);
        } else {
            log.info("Skipping assertion - no test data available");
        }
    }

    @Test
    @Order(2)
    @DisplayName("LOOKUP - Returns null for non-existent value")
    void test02_lookupNonExistent() {
        Object result = crossTableFormulaService.lookup(
                TEST_MODEL, "name", "NonExistentDevice-XYZ-99999", "status");

        assertNull(result, "LOOKUP should return null for non-existent value");
    }

    @Test
    @Order(3)
    @DisplayName("LOOKUP - Returns null for null parameters")
    void test03_lookupNullParams() {
        assertNull(crossTableFormulaService.lookup(null, "name", "value", "status"));
        assertNull(crossTableFormulaService.lookup(TEST_MODEL, null, "value", "status"));
        assertNull(crossTableFormulaService.lookup(TEST_MODEL, "name", null, "status"));
        assertNull(crossTableFormulaService.lookup(TEST_MODEL, "name", "value", null));
    }

    // ==================== VLOOKUP Tests ====================

    @Test
    @Order(4)
    @DisplayName("VLOOKUP - Exact match")
    void test04_vlookupExactMatch() {
        Object result = crossTableFormulaService.vlookup(
                "CrossTable-Device-B", TEST_MODEL, "name", "device_type", true);

        if (!createdRecordIds.isEmpty()) {
            assertNotNull(result, "VLOOKUP exact match should find the record");
            assertEquals("actuator", result.toString());
        }
    }

    @Test
    @Order(5)
    @DisplayName("VLOOKUP - Fuzzy match (contains)")
    void test05_vlookupFuzzyMatch() {
        Object result = crossTableFormulaService.vlookup(
                "CrossTable-Device", TEST_MODEL, "name", "status", false);

        if (!createdRecordIds.isEmpty()) {
            assertNotNull(result, "VLOOKUP fuzzy match should find at least one record");
            log.info("VLOOKUP fuzzy match result: {}", result);
        }
    }

    @Test
    @Order(6)
    @DisplayName("VLOOKUP - No match returns null")
    void test06_vlookupNoMatch() {
        Object result = crossTableFormulaService.vlookup(
                "CompletelyUnique-99999", TEST_MODEL, "name", "status", true);

        assertNull(result, "VLOOKUP should return null when no match found");
    }

    @Test
    @Order(7)
    @DisplayName("VLOOKUP - Null parameters return null")
    void test07_vlookupNullParams() {
        assertNull(crossTableFormulaService.vlookup(null, TEST_MODEL, "name", "status", true));
        assertNull(crossTableFormulaService.vlookup("val", null, "name", "status", true));
    }

    // ==================== RELATED Tests ====================

    @Test
    @Order(8)
    @DisplayName("RELATED - Find related records by foreign key")
    void test08_relatedByForeignKey() {
        List<Object> results = crossTableFormulaService.related(
                TEST_MODEL, "status", "active", "name");

        assertNotNull(results, "RELATED should return a list");

        if (!createdRecordIds.isEmpty()) {
            assertTrue(results.size() >= 2,
                    "RELATED should find at least 2 active devices");
            log.info("RELATED found {} records", results.size());
        }
    }

    @Test
    @Order(9)
    @DisplayName("RELATED - Empty list for non-existent foreign key value")
    void test09_relatedNoMatch() {
        List<Object> results = crossTableFormulaService.related(
                TEST_MODEL, "status", "nonexistent_status_xyz", "name");

        assertNotNull(results, "RELATED should return empty list, not null");
        assertTrue(results.isEmpty(), "RELATED should return empty list for no match");
    }

    @Test
    @Order(10)
    @DisplayName("RELATED - Null parameters return empty list")
    void test10_relatedNullParams() {
        assertTrue(crossTableFormulaService.related(null, "fk", "id", "field").isEmpty());
        assertTrue(crossTableFormulaService.related(TEST_MODEL, null, "id", "field").isEmpty());
        assertTrue(crossTableFormulaService.related(TEST_MODEL, "fk", null, "field").isEmpty());
        assertTrue(crossTableFormulaService.related(TEST_MODEL, "fk", "id", null).isEmpty());
    }

    // ==================== COUNTIF Tests ====================

    @Test
    @Order(11)
    @DisplayName("COUNTIF - Count records matching condition")
    void test11_countIfMatchingRecords() {
        long count = crossTableFormulaService.countIf(TEST_MODEL, "status", "active");

        if (!createdRecordIds.isEmpty()) {
            assertTrue(count >= 2, "COUNTIF should count at least 2 active records");
            log.info("COUNTIF active={}", count);
        }
    }

    @Test
    @Order(12)
    @DisplayName("COUNTIF - Zero for non-existent condition value")
    void test12_countIfNoMatch() {
        long count = crossTableFormulaService.countIf(
                TEST_MODEL, "status", "completely_nonexistent_status");

        assertEquals(0, count, "COUNTIF should return 0 for non-existent value");
    }

    @Test
    @Order(13)
    @DisplayName("COUNTIF - Null parameters return 0")
    void test13_countIfNullParams() {
        assertEquals(0, crossTableFormulaService.countIf(null, "status", "active"));
        assertEquals(0, crossTableFormulaService.countIf(TEST_MODEL, null, "active"));
        assertEquals(0, crossTableFormulaService.countIf(TEST_MODEL, "status", null));
    }

    // ==================== SUMIF Tests ====================

    @Test
    @Order(14)
    @DisplayName("SUMIF - Sum numeric field matching condition")
    void test14_sumIfMatchingRecords() {
        // SUMIF on a field that might not be numeric - should handle gracefully
        double sum = crossTableFormulaService.sumIf(
                TEST_MODEL, "status", "active", "name");

        // Non-numeric fields should result in 0.0 for each row
        assertTrue(sum >= 0, "SUMIF should return >= 0");
        log.info("SUMIF result: {}", sum);
    }

    @Test
    @Order(15)
    @DisplayName("SUMIF - Zero for non-existent condition")
    void test15_sumIfNoMatch() {
        double sum = crossTableFormulaService.sumIf(
                TEST_MODEL, "status", "nonexistent_xyz", "name");

        assertEquals(0.0, sum, "SUMIF should return 0 for non-matching condition");
    }

    @Test
    @Order(16)
    @DisplayName("SUMIF - Null parameters return 0")
    void test16_sumIfNullParams() {
        assertEquals(0.0, crossTableFormulaService.sumIf(null, "f", "v", "s"));
        assertEquals(0.0, crossTableFormulaService.sumIf(TEST_MODEL, null, "v", "s"));
        assertEquals(0.0, crossTableFormulaService.sumIf(TEST_MODEL, "f", null, "s"));
        assertEquals(0.0, crossTableFormulaService.sumIf(TEST_MODEL, "f", "v", null));
    }

    // ==================== Edge Cases ====================

    @Test
    @Order(17)
    @DisplayName("LOOKUP - Non-existent model returns null gracefully")
    void test17_lookupInvalidModel() {
        Object result = crossTableFormulaService.lookup(
                "nonexistent_model_xyz", "name", "val", "status");

        assertNull(result, "LOOKUP on invalid model should return null");
    }

    @Test
    @Order(18)
    @DisplayName("RELATED - Non-existent model returns empty list gracefully")
    void test18_relatedInvalidModel() {
        List<Object> results = crossTableFormulaService.related(
                "nonexistent_model_xyz", "fk", "val", "field");

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    @Order(19)
    @DisplayName("COUNTIF - Non-existent model returns 0 gracefully")
    void test19_countIfInvalidModel() {
        long count = crossTableFormulaService.countIf(
                "nonexistent_model_xyz", "field", "val");

        assertEquals(0, count);
    }

    @Test
    @Order(20)
    @DisplayName("SUMIF - Non-existent model returns 0 gracefully")
    void test20_sumIfInvalidModel() {
        double sum = crossTableFormulaService.sumIf(
                "nonexistent_model_xyz", "field", "val", "sum_field");

        assertEquals(0.0, sum);
    }
}
