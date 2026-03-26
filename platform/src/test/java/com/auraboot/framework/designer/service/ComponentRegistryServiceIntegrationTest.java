//package com.auraboot.framework.designer.service;
//
//import com.auraboot.framework.designer.dto.*;
//import com.auraboot.framework.integration.BaseIntegrationTest;
//import com.auraboot.framework.meta.dto.PaginationRequest;
//import com.auraboot.framework.meta.dto.PaginationResult;
//import lombok.extern.slf4j.Slf4j;
//import org.junit.jupiter.api.*;
//import org.springframework.beans.factory.annotation.Autowired;
//
//import java.util.*;
//
//import static org.junit.jupiter.api.Assertions.*;
//
///**
// * ComponentRegistryService Integration Test
// *
// * Covers P3-3 requirements:
// * 1. Component CRUD (create, find, update, delete)
// * 2. Enable/disable management
// * 3. Category and type filtering
// * 4. Tag-based queries
// * 5. Import/export components
// * 6. Sort weight management
// * 7. Component copy
// * 8. Statistics
// */
//@Slf4j
//@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
//@DisplayName("ComponentRegistryService Integration Test - P3-3")
//class ComponentRegistryServiceIntegrationTest extends BaseIntegrationTest {
//
//    @Autowired
//    private ComponentRegistryService componentRegistryService;
//
//    private static String createdComponentPid;
//    private static final String TEST_COMPONENT_NAME = "comp_test_" + System.currentTimeMillis();
//
//    // ==================== Create Tests ====================
//
//    @Test
//    @Order(1)
//    @DisplayName("P3-3.1: Create component with full schema")
//    void test01_createComponent() {
//        ComponentRegistryCreateRequest request = new ComponentRegistryCreateRequest();
//        request.setName(TEST_COMPONENT_NAME);
//        request.setType("form");
//        request.setDisplayName("Test Input Component");
//        request.setDescription("Integration test component");
//        request.setCategory("input");
//        request.setVersion("1.0.0");
//        request.setIcon("input-icon");
//        request.setTags(new String[]{"form", "input", "test"});
//        request.setComponentSchema(Map.of(
//                "type", "object",
//                "properties", Map.of("value", Map.of("type", "string"))
//        ));
//        request.setPropsSchema(Map.of(
//                "placeholder", Map.of("type", "string", "default", "Enter value")
//        ));
//        request.setDefaultProps(Map.of("placeholder", "Enter value"));
//        request.setIsBuiltin(false);
//        request.setIsEnabled(true);
//        request.setSortWeight(10);
//
//        ComponentRegistryDTO result = componentRegistryService.create(request);
//
//        assertNotNull(result);
//        assertNotNull(result.getPid());
//        assertEquals(TEST_COMPONENT_NAME, result.getName());
//        assertEquals("form", result.getType());
//        assertEquals("Test Input Component", result.getDisplayName());
//        assertEquals("input", result.getCategory());
//        assertTrue(result.getIsEnabled());
//
//        createdComponentPid = result.getPid();
//        log.info("Created component: pid={}, name={}", createdComponentPid, TEST_COMPONENT_NAME);
//    }
//
//    @Test
//    @Order(2)
//    @DisplayName("P3-3.1: Create builtin component")
//    void test02_createBuiltinComponent() {
//        ComponentRegistryCreateRequest request = new ComponentRegistryCreateRequest();
//        request.setName("builtin_comp_" + System.currentTimeMillis());
//        request.setType("layout");
//        request.setDisplayName("Builtin Layout");
//        request.setCategory("layout");
//        request.setComponentSchema(Map.of("type", "container"));
//        request.setIsBuiltin(true);
//        request.setIsEnabled(true);
//
//        ComponentRegistryDTO result = componentRegistryService.create(request);
//
//        assertNotNull(result);
//        assertTrue(result.getIsBuiltin());
//    }
//
//    // ==================== Find Tests ====================
//
//    @Test
//    @Order(10)
//    @DisplayName("P3-3.2: Find component by PID")
//    void test10_findByPid() {
//        assertNotNull(createdComponentPid);
//
//        ComponentRegistryDTO result = componentRegistryService.findByPid(createdComponentPid);
//
//        assertNotNull(result);
//        assertEquals(createdComponentPid, result.getPid());
//        assertEquals(TEST_COMPONENT_NAME, result.getName());
//    }
//
//    @Test
//    @Order(11)
//    @DisplayName("P3-3.2: Find component by name")
//    void test11_findByName() {
//        ComponentRegistryDTO result = componentRegistryService.findByName(TEST_COMPONENT_NAME);
//
//        assertNotNull(result);
//        assertEquals(TEST_COMPONENT_NAME, result.getName());
//    }
//
//    @Test
//    @Order(12)
//    @DisplayName("P3-3.2: Find by type")
//    void test12_findByType() {
//        List<ComponentRegistryDTO> results = componentRegistryService.findByType("form");
//
//        assertNotNull(results);
//        assertTrue(results.stream().anyMatch(c -> TEST_COMPONENT_NAME.equals(c.getName())));
//    }
//
//    @Test
//    @Order(13)
//    @DisplayName("P3-3.2: Find by category")
//    void test13_findByCategory() {
//        List<ComponentRegistryDTO> results = componentRegistryService.findByCategory("input");
//
//        assertNotNull(results);
//        assertTrue(results.stream().anyMatch(c -> TEST_COMPONENT_NAME.equals(c.getName())));
//    }
//
//    @Test
//    @Order(14)
//    @DisplayName("P3-3.2: Find by tag")
//    void test14_findByTag() {
//        List<ComponentRegistryDTO> results = componentRegistryService.findByTag("test");
//
//        assertNotNull(results);
//        assertTrue(results.stream().anyMatch(c -> TEST_COMPONENT_NAME.equals(c.getName())));
//    }
//
//    @Test
//    @Order(15)
//    @DisplayName("P3-3.2: Find enabled components")
//    void test15_findEnabledComponents() {
//        List<ComponentRegistryDTO> results = componentRegistryService.findEnabledComponents();
//
//        assertNotNull(results);
//        assertTrue(results.stream().allMatch(ComponentRegistryDTO::getIsEnabled));
//    }
//
//    @Test
//    @Order(16)
//    @DisplayName("P3-3.2: Find builtin components")
//    void test16_findBuiltinComponents() {
//        List<ComponentRegistryDTO> results = componentRegistryService.findBuiltinComponents();
//
//        assertNotNull(results);
//        assertTrue(results.stream().allMatch(ComponentRegistryDTO::getIsBuiltin));
//    }
//
//    // ==================== List/Pagination Tests ====================
//
//    @Test
//    @Order(20)
//    @DisplayName("P3-3.3: List components with pagination")
//    void test20_listWithPagination() {
//        ComponentRegistryQueryRequest queryRequest = new ComponentRegistryQueryRequest();
//        PaginationRequest paginationRequest = new PaginationRequest();
//        paginationRequest.setPageNum(1);
//        paginationRequest.setPageSize(10);
//
//        PaginationResult<ComponentRegistryDTO> result =
//                componentRegistryService.list(queryRequest, paginationRequest);
//
//        assertNotNull(result);
//        assertNotNull(result.getRecords());
//        assertTrue(result.getTotal() > 0);
//    }
//
//    @Test
//    @Order(21)
//    @DisplayName("P3-3.3: List components filtered by type")
//    void test21_listFilteredByType() {
//        ComponentRegistryQueryRequest queryRequest = new ComponentRegistryQueryRequest();
//        queryRequest.setType("form");
//        PaginationRequest paginationRequest = new PaginationRequest();
//        paginationRequest.setPageNum(1);
//        paginationRequest.setPageSize(50);
//
//        PaginationResult<ComponentRegistryDTO> result =
//                componentRegistryService.list(queryRequest, paginationRequest);
//
//        assertNotNull(result);
//        assertTrue(result.getRecords().stream().allMatch(c -> "form".equals(c.getType())));
//    }
//
//    // ==================== Update Tests ====================
//
//    @Test
//    @Order(30)
//    @DisplayName("P3-3.4: Update component")
//    void test30_updateComponent() {
//        assertNotNull(createdComponentPid);
//
//        ComponentRegistryUpdateRequest request = new ComponentRegistryUpdateRequest();
//        request.setDisplayName("Updated Component Name");
//        request.setDescription("Updated description");
//        request.setCategory("updated_category");
//
//        ComponentRegistryDTO result = componentRegistryService.update(createdComponentPid, request);
//
//        assertNotNull(result);
//        assertEquals("Updated Component Name", result.getDisplayName());
//        assertEquals("updated_category", result.getCategory());
//    }
//
//    // ==================== Enable/Disable Tests ====================
//
//    @Test
//    @Order(40)
//    @DisplayName("P3-3.5: Disable component")
//    void test40_disableComponent() {
//        assertNotNull(createdComponentPid);
//
//        assertDoesNotThrow(() -> {
//            componentRegistryService.disable(createdComponentPid);
//        });
//
//        ComponentRegistryDTO result = componentRegistryService.findByPid(createdComponentPid);
//        assertFalse(result.getIsEnabled());
//    }
//
//    @Test
//    @Order(41)
//    @DisplayName("P3-3.5: Enable component")
//    void test41_enableComponent() {
//        assertNotNull(createdComponentPid);
//
//        assertDoesNotThrow(() -> {
//            componentRegistryService.enable(createdComponentPid);
//        });
//
//        ComponentRegistryDTO result = componentRegistryService.findByPid(createdComponentPid);
//        assertTrue(result.getIsEnabled());
//    }
//
//    @Test
//    @Order(42)
//    @DisplayName("P3-3.5: Batch enable/disable")
//    void test42_batchEnableDisable() {
//        // Create two components for batch testing
//        ComponentRegistryCreateRequest req1 = new ComponentRegistryCreateRequest();
//        req1.setName("batch_1_" + System.currentTimeMillis());
//        req1.setType("form");
//        req1.setDisplayName("Batch 1");
//        req1.setCategory("batch");
//        req1.setComponentSchema(Map.of("type", "text"));
//        ComponentRegistryDTO comp1 = componentRegistryService.create(req1);
//
//        ComponentRegistryCreateRequest req2 = new ComponentRegistryCreateRequest();
//        req2.setName("batch_2_" + System.currentTimeMillis());
//        req2.setType("form");
//        req2.setDisplayName("Batch 2");
//        req2.setCategory("batch");
//        req2.setComponentSchema(Map.of("type", "text"));
//        ComponentRegistryDTO comp2 = componentRegistryService.create(req2);
//
//        List<String> pids = List.of(comp1.getPid(), comp2.getPid());
//
//        // Batch disable
//        assertDoesNotThrow(() -> componentRegistryService.batchDisable(pids));
//
//        // Batch enable
//        assertDoesNotThrow(() -> componentRegistryService.batchEnable(pids));
//    }
//
//    // ==================== Sort Weight Tests ====================
//
//    @Test
//    @Order(50)
//    @DisplayName("P3-3.6: Update sort weight")
//    void test50_updateSortWeight() {
//        assertNotNull(createdComponentPid);
//
//        assertDoesNotThrow(() -> {
//            componentRegistryService.updateSortWeight(createdComponentPid, 99);
//        });
//
//        ComponentRegistryDTO result = componentRegistryService.findByPid(createdComponentPid);
//        assertEquals(99, result.getSortWeight());
//    }
//
//    @Test
//    @Order(51)
//    @DisplayName("P3-3.6: Batch update sort weights")
//    void test51_batchUpdateSortWeight() {
//        assertNotNull(createdComponentPid);
//
//        Map<String, Integer> weights = Map.of(createdComponentPid, 50);
//
//        assertDoesNotThrow(() -> {
//            componentRegistryService.batchUpdateSortWeight(weights);
//        });
//    }
//
//    // ==================== Copy Tests ====================
//
//    @Test
//    @Order(60)
//    @DisplayName("P3-3.7: Copy component")
//    void test60_copyComponent() {
//        assertNotNull(createdComponentPid);
//
//        String newName = "copy_" + System.currentTimeMillis();
//        ComponentRegistryDTO copy = componentRegistryService.copy(createdComponentPid, newName);
//
//        assertNotNull(copy);
//        assertNotNull(copy.getPid());
//        assertNotEquals(createdComponentPid, copy.getPid());
//        assertEquals(newName, copy.getName());
//    }
//
//    // ==================== Import/Export Tests ====================
//
//    @Test
//    @Order(70)
//    @DisplayName("P3-3.8: Export component")
//    void test70_exportComponent() {
//        assertNotNull(createdComponentPid);
//
//        Map<String, Object> exported = componentRegistryService.exportComponent(createdComponentPid);
//
//        assertNotNull(exported);
//        assertFalse(exported.isEmpty());
//    }
//
//    @Test
//    @Order(71)
//    @DisplayName("P3-3.8: Import component from exported data")
//    void test71_importComponent() {
//        Map<String, Object> componentData = new HashMap<>();
//        componentData.put("name", "imported_" + System.currentTimeMillis());
//        componentData.put("type", "form");
//        componentData.put("displayName", "Imported Component");
//        componentData.put("category", "imported");
//        componentData.put("componentSchema", Map.of("type", "text"));
//
//        ComponentRegistryDTO imported = componentRegistryService.importComponent(componentData);
//
//        assertNotNull(imported);
//        assertNotNull(imported.getPid());
//    }
//
//    @Test
//    @Order(72)
//    @DisplayName("P3-3.8: Batch export components")
//    void test72_batchExport() {
//        assertNotNull(createdComponentPid);
//
//        List<Map<String, Object>> exported =
//                componentRegistryService.batchExportComponents(List.of(createdComponentPid));
//
//        assertNotNull(exported);
//        assertFalse(exported.isEmpty());
//    }
//
//    // ==================== Statistics Tests ====================
//
//    @Test
//    @Order(80)
//    @DisplayName("P3-3.9: Count total components")
//    void test80_count() {
//        long count = componentRegistryService.count();
//        assertTrue(count > 0);
//    }
//
//    @Test
//    @Order(81)
//    @DisplayName("P3-3.9: Count by category")
//    void test81_countByCategory() {
//        long count = componentRegistryService.countByCategory("input");
//        assertTrue(count >= 0);
//    }
//
//    @Test
//    @Order(82)
//    @DisplayName("P3-3.9: Count enabled components")
//    void test82_countEnabled() {
//        long count = componentRegistryService.countEnabled();
//        assertTrue(count >= 0);
//    }
//
//    @Test
//    @Order(83)
//    @DisplayName("P3-3.9: Get all categories")
//    void test83_getAllCategories() {
//        List<String> categories = componentRegistryService.getAllCategories();
//        assertNotNull(categories);
//    }
//
//    @Test
//    @Order(84)
//    @DisplayName("P3-3.9: Get all types")
//    void test84_getAllTypes() {
//        List<String> types = componentRegistryService.getAllTypes();
//        assertNotNull(types);
//    }
//
//    @Test
//    @Order(85)
//    @DisplayName("P3-3.9: Get all tags")
//    void test85_getAllTags() {
//        List<String> tags = componentRegistryService.getAllTags();
//        assertNotNull(tags);
//    }
//
//    @Test
//    @Order(86)
//    @DisplayName("P3-3.9: Check name existence")
//    void test86_existsByName() {
//        boolean exists = componentRegistryService.existsByName(TEST_COMPONENT_NAME, null);
//        assertTrue(exists);
//
//        boolean existsExcluding = componentRegistryService.existsByName(TEST_COMPONENT_NAME, createdComponentPid);
//        assertFalse(existsExcluding);
//    }
//
//    // ==================== Delete Tests ====================
//
//    @Test
//    @Order(90)
//    @DisplayName("P3-3.10: Delete component")
//    void test90_deleteComponent() {
//        ComponentRegistryCreateRequest request = new ComponentRegistryCreateRequest();
//        request.setName("to_delete_" + System.currentTimeMillis());
//        request.setType("form");
//        request.setDisplayName("To Delete");
//        request.setCategory("delete");
//        request.setComponentSchema(Map.of("type", "text"));
//
//        ComponentRegistryDTO created = componentRegistryService.create(request);
//        assertNotNull(created.getPid());
//
//        assertDoesNotThrow(() -> {
//            componentRegistryService.delete(created.getPid());
//        });
//    }
//
//    @Test
//    @Order(91)
//    @DisplayName("P3-3.10: Batch delete components")
//    void test91_batchDelete() {
//        ComponentRegistryCreateRequest req1 = new ComponentRegistryCreateRequest();
//        req1.setName("bd_1_" + System.currentTimeMillis());
//        req1.setType("form");
//        req1.setDisplayName("BD 1");
//        req1.setCategory("batch_delete");
//        req1.setComponentSchema(Map.of("type", "text"));
//        ComponentRegistryDTO comp1 = componentRegistryService.create(req1);
//
//        ComponentRegistryCreateRequest req2 = new ComponentRegistryCreateRequest();
//        req2.setName("bd_2_" + System.currentTimeMillis());
//        req2.setType("form");
//        req2.setDisplayName("BD 2");
//        req2.setCategory("batch_delete");
//        req2.setComponentSchema(Map.of("type", "text"));
//        ComponentRegistryDTO comp2 = componentRegistryService.create(req2);
//
//        assertDoesNotThrow(() -> {
//            componentRegistryService.batchDelete(List.of(comp1.getPid(), comp2.getPid()));
//        });
//    }
//}
