package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.FilterPresetCreateRequest;
import com.auraboot.framework.meta.entity.FilterPreset;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FilterPresetService Integration Test
 *
 * Covers P3-1.4 requirements:
 * 1. Filter preset CRUD (create, list, update, delete)
 * 2. Global vs personal presets
 * 3. Default preset management
 * 4. AND/OR logic operators
 * 
 * Each test is self-contained and creates its own test data.
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("FilterPresetService Integration Test - P3-1")
class FilterPresetServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private FilterPresetService filterPresetService;

    // ==================== Helper Methods ====================

    private String generatePageCode() {
        return "page_orders_" + System.currentTimeMillis() + "_" + Math.random();
    }

    private FilterPreset createPreset(String pageCode, String name, String logic, String scope, boolean isDefault) {
        FilterPresetCreateRequest request = new FilterPresetCreateRequest();
        request.setPageCode(pageCode);
        request.setModelCode("test_order_model");
        request.setName(name);
        request.setConditions("[{\"field\":\"status\",\"operator\":\"eq\",\"value\":\"ACTIVE\"}]");
        request.setLogic(logic);
        request.setDefault(isDefault);
        request.setScope(scope);
        return filterPresetService.create(request);
    }

    // ==================== Create Tests ====================

    @Test
    @Order(1)
    @DisplayName("P3-1.4: Create personal filter preset with AND logic")
    void test01_createPersonalPreset() {
        String pageCode = generatePageCode();
        
        FilterPresetCreateRequest request = new FilterPresetCreateRequest();
        request.setPageCode(pageCode);
        request.setModelCode("test_order_model");
        request.setName("Active High-Value Orders");
        request.setConditions("[{\"field\":\"status\",\"operator\":\"eq\",\"value\":\"ACTIVE\"},{\"field\":\"amount\",\"operator\":\"gt\",\"value\":1000}]");
        request.setLogic("and");
        request.setDefault(false);
        request.setScope("personal");

        FilterPreset result = filterPresetService.create(request);

        assertNotNull(result);
        assertNotNull(result.getId());
        assertEquals(pageCode, result.getPageCode());
        assertEquals("test_order_model", result.getModelCode());
        assertEquals("Active High-Value Orders", result.getName());
        assertEquals("and", result.getLogic());

        log.info("Created filter preset: id={}", result.getId());
    }

    @Test
    @Order(2)
    @DisplayName("P3-1.4: Create global filter preset with OR logic")
    void test02_createGlobalPreset() {
        String pageCode = generatePageCode();
        
        FilterPresetCreateRequest request = new FilterPresetCreateRequest();
        request.setPageCode(pageCode);
        request.setModelCode("test_order_model");
        request.setName("Urgent or Overdue");
        request.setConditions("[{\"field\":\"priority\",\"operator\":\"eq\",\"value\":\"URGENT\"},{\"field\":\"dueDate\",\"operator\":\"lt\",\"value\":\"2024-01-01\"}]");
        request.setLogic("OR");
        request.setDefault(false);
        request.setScope("global");

        FilterPreset result = filterPresetService.create(request);

        assertNotNull(result);
        assertNotNull(result.getId());
        assertEquals("OR", result.getLogic());
    }

    @Test
    @Order(3)
    @DisplayName("P3-1.4: Create preset with default flag")
    void test03_createDefaultPreset() {
        String pageCode = generatePageCode();
        
        FilterPresetCreateRequest request = new FilterPresetCreateRequest();
        request.setPageCode(pageCode);
        request.setModelCode("test_order_model");
        request.setName("Default Filter");
        request.setConditions("[{\"field\":\"status\",\"operator\":\"eq\",\"value\":\"PENDING\"}]");
        request.setLogic("and");
        request.setDefault(true);
        request.setScope("personal");

        FilterPreset result = filterPresetService.create(request);

        assertNotNull(result);
    }

    // ==================== List Tests ====================

    @Test
    @Order(10)
    @DisplayName("P3-1.4: List presets by page code")
    void test10_listByPageCode() {
        String pageCode = generatePageCode();
        
        // Create multiple presets for the same page
        createPreset(pageCode, "Preset 1", "and", "personal", false);
        createPreset(pageCode, "Preset 2", "OR", "global", false);

        List<FilterPreset> presets = filterPresetService.listByPageCode(pageCode);

        assertNotNull(presets);
        assertTrue(presets.size() >= 2, "Should have at least personal + global presets");
        assertTrue(presets.stream().allMatch(p -> pageCode.equals(p.getPageCode())));
    }

    @Test
    @Order(11)
    @DisplayName("P3-1.4: List presets for non-existent page returns empty")
    void test11_listByPageCode_empty() {
        List<FilterPreset> presets = filterPresetService.listByPageCode("non_existent_page_" + System.currentTimeMillis());

        assertNotNull(presets);
        assertTrue(presets.isEmpty());
    }

    // ==================== Update Tests ====================

    @Test
    @Order(20)
    @DisplayName("P3-1.4: Update filter preset name and conditions")
    void test20_updatePreset() {
        String pageCode = generatePageCode();
        FilterPreset created = createPreset(pageCode, "Original Name", "and", "personal", false);

        FilterPresetCreateRequest request = new FilterPresetCreateRequest();
        request.setPageCode(pageCode);
        request.setModelCode("test_order_model");
        request.setName("Updated Filter Name");
        request.setConditions("[{\"field\":\"status\",\"operator\":\"eq\",\"value\":\"COMPLETED\"}]");
        request.setLogic("and");

        FilterPreset result = filterPresetService.update(created.getId(), request);

        assertNotNull(result);
        assertEquals("Updated Filter Name", result.getName());
    }

    @Test
    @Order(21)
    @DisplayName("P3-1.4: Update preset logic to OR")
    void test21_updatePresetLogic() {
        String pageCode = generatePageCode();
        FilterPreset created = createPreset(pageCode, "Logic Test", "and", "personal", false);

        FilterPresetCreateRequest request = new FilterPresetCreateRequest();
        request.setPageCode(pageCode);
        request.setModelCode("test_order_model");
        request.setName("Logic Test");
        request.setConditions("[{\"field\":\"a\",\"operator\":\"eq\",\"value\":\"1\"},{\"field\":\"b\",\"operator\":\"eq\",\"value\":\"2\"}]");
        request.setLogic("OR");

        FilterPreset result = filterPresetService.update(created.getId(), request);

        assertNotNull(result);
        assertEquals("OR", result.getLogic());
    }

    // ==================== Set Default Tests ====================

    @Test
    @Order(30)
    @DisplayName("P3-1.4: Set preset as default")
    void test30_setDefault() {
        String pageCode = generatePageCode();
        FilterPreset created = createPreset(pageCode, "Default Test", "and", "personal", false);

        assertDoesNotThrow(() -> {
            filterPresetService.setDefault(created.getId());
        });
    }

    @Test
    @Order(31)
    @DisplayName("P3-1.4: Setting new default unsets previous default")
    void test31_setDefault_unsetsOld() {
        String pageCode = generatePageCode();
        
        FilterPreset preset1 = createPreset(pageCode, "Default Candidate 1", "and", "personal", false);
        FilterPreset preset2 = createPreset(pageCode, "Default Candidate 2", "and", "personal", false);

        filterPresetService.setDefault(preset1.getId());
        filterPresetService.setDefault(preset2.getId());

        // Only the last one should be default
        List<FilterPreset> presets = filterPresetService.listByPageCode(pageCode);
        long defaultCount = presets.stream()
                .filter(p -> Boolean.TRUE.equals(p.getIsDefault()))
                .count();
        assertTrue(defaultCount <= 1, "At most one preset should be default");
    }

    // ==================== Delete Tests ====================

    @Test
    @Order(90)
    @DisplayName("P3-1.4: Delete filter preset")
    void test90_deletePreset() {
        String pageCode = generatePageCode();
        FilterPreset created = createPreset(pageCode, "To Delete", "and", "personal", false);

        assertDoesNotThrow(() -> {
            filterPresetService.delete(created.getId());
        });

        // Verify deletion
        List<FilterPreset> remaining = filterPresetService.listByPageCode(pageCode);
        assertFalse(remaining.stream().anyMatch(p -> created.getId().equals(p.getId())));
    }
}
