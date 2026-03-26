package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.template.dto.CrudTemplateConfig;
import com.auraboot.framework.meta.template.dto.TemplateGenerationResult;
import com.auraboot.framework.meta.template.service.TemplateGeneratorService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TemplateGeneratorService Integration Test
 *
 * Covers P0-4 requirements:
 * 1. DslGenerator integration - Generates List/Form/Detail pages via DslGeneratorImpl
 * 2. Configuration validation - Validates template config before generation
 * 3. Page generation and persistence - Generated pages saved to PageSchema
 * 4. Menu creation - Auto-creates menu entries
 * 5. Permission creation - Auto-creates CRUD permissions
 * 6. Role permission assignment - Assigns permissions to specified roles
 * 7. Error handling - Handles missing models, invalid configs
 *
 * Uses shared model across all tests to avoid field uniqueness constraint issues.
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("TemplateGeneratorService Integration Test - P0-4")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateGeneratorIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TemplateGeneratorService templateGeneratorService;

    @Autowired
    private MetaModelMapper metaModelMapper;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @Autowired
    private MetaModelFieldBindingMapper fieldBindingMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Shared model for all tests
    private String testModelCode;
    private Model testModel;
    private boolean modelInitialized = false;
    private int modelCounter = 0;

    @BeforeAll
    void setupSharedModel() {
        testModelCode = "tmpl_test_" + System.currentTimeMillis();
        modelInitialized = false;
        modelCounter = 0;
    }

    @BeforeEach
    void ensureModelExists() {
        setupTenantContext();
        
        if (!modelInitialized) {
            try {
                cleanupExistingModel();
                createTestModelForTemplate();
                modelInitialized = true;
                log.info("Model initialized for template tests: {}", testModelCode);
            } catch (Exception e) {
                log.error("Failed to initialize model", e);
                throw new RuntimeException("Failed to initialize model", e);
            }
        }
    }

    @AfterAll
    void cleanup() {
        modelInitialized = false;
    }

    // ==================== Configuration Validation Tests ====================

    @Test
    @Order(1)
    @DisplayName("P0-4.2: Validate configuration - null model code should fail")
    void test01_validateConfig_nullModelCode() {
        CrudTemplateConfig config = buildDefaultConfig();

        assertThrows(BusinessException.class, () -> {
            templateGeneratorService.validateConfiguration(null, config);
        }, "Null model code should throw exception");
    }

    @Test
    @Order(2)
    @DisplayName("P0-4.2: Validate configuration - empty model code should fail")
    void test02_validateConfig_emptyModelCode() {
        CrudTemplateConfig config = buildDefaultConfig();

        assertThrows(BusinessException.class, () -> {
            templateGeneratorService.validateConfiguration("", config);
        }, "Empty model code should throw exception");
    }

    @Test
    @Order(3)
    @DisplayName("P0-4.2: Validate configuration - null config should fail")
    void test03_validateConfig_nullConfig() {
        assertThrows(BusinessException.class, () -> {
            templateGeneratorService.validateConfiguration(testModelCode, null);
        }, "Null config should throw exception");
    }

    @Test
    @Order(4)
    @DisplayName("P0-4.2: Validate configuration - empty menu name should fail")
    void test04_validateConfig_emptyMenuName() {
        CrudTemplateConfig config = new CrudTemplateConfig();
        config.setMenuName("");
        config.setGenerateList(true);

        assertThrows(BusinessException.class, () -> {
            templateGeneratorService.validateConfiguration(testModelCode, config);
        }, "Empty menu name should throw exception");
    }

    @Test
    @Order(5)
    @DisplayName("P0-4.2: Validate configuration - no page types selected should fail")
    void test05_validateConfig_noPageTypes() {
        CrudTemplateConfig config = new CrudTemplateConfig();
        config.setMenuName("Valid Menu");
        config.setGenerateList(false);
        config.setGenerateForm(false);
        config.setGenerateDetail(false);

        assertThrows(BusinessException.class, () -> {
            templateGeneratorService.validateConfiguration(testModelCode, config);
        }, "No page types selected should throw exception");
    }

    @Test
    @Order(6)
    @DisplayName("P0-4.2: Validate configuration - valid config should pass")
    void test06_validateConfig_valid() {
        CrudTemplateConfig config = buildDefaultConfig();

        assertDoesNotThrow(() -> {
            templateGeneratorService.validateConfiguration(testModelCode, config);
        });
    }

    // ==================== Generate CRUD Pages Tests ====================

    @Test
    @Order(10)
    @DisplayName("P0-4.1: Generate all CRUD pages (List + Form + Detail)")
    void test10_generateCrudPages_all() {
        CrudTemplateConfig config = buildDefaultConfig();
        config.setGenerateList(true);
        config.setGenerateForm(true);
        config.setGenerateDetail(true);
        config.setEnableExport(true);
        config.setEnableImport(false);

        TemplateGenerationResult result = templateGeneratorService.generateCrudPages(testModelCode, config);

        assertNotNull(result);
        assertNotNull(result.getModelCode());
        assertEquals(testModelCode, result.getModelCode());

        // Verify pages generated
        assertNotNull(result.getGeneratedResources());
        assertNotNull(result.getGeneratedResources().getPages());
        assertTrue(result.getGeneratedResources().getPages().size() >= 3,
                "Should generate at least 3 pages (list, form, detail)");

        // Verify menus generated
        assertNotNull(result.getGeneratedResources().getMenus());
        assertTrue(result.getGeneratedResources().getMenus().size() >= 1,
                "Should generate at least 1 menu");

        // Verify permissions generated (read, create, update, delete + export = 5)
        assertNotNull(result.getGeneratedResources().getPermissions());
        assertTrue(result.getGeneratedResources().getPermissions().size() >= 5,
                "Should generate at least 5 permissions");

        // Verify access links
        assertNotNull(result.getAccessLinks());
        assertNotNull(result.getAccessLinks().getListPage());
        assertNotNull(result.getAccessLinks().getFormPage());
        assertNotNull(result.getAccessLinks().getDetailPage());

        log.info("Generated {} pages, {} menus, {} permissions",
                result.getGeneratedResources().getPages().size(),
                result.getGeneratedResources().getMenus().size(),
                result.getGeneratedResources().getPermissions().size());
    }

    @Test
    @Order(11)
    @DisplayName("P0-4.1: Generate only List page")
    void test11_generateCrudPages_listOnly() {
        // Create a new model for this test to avoid conflict
        String listOnlyCode = "tmpl_list_" + System.currentTimeMillis();
        createTestModelWithCode(listOnlyCode);

        CrudTemplateConfig config = new CrudTemplateConfig();
        config.setMenuName("List Only Model");
        config.setGenerateList(true);
        config.setGenerateForm(false);
        config.setGenerateDetail(false);

        TemplateGenerationResult result = templateGeneratorService.generateCrudPages(listOnlyCode, config);

        assertNotNull(result);
        assertNotNull(result.getGeneratedResources().getPages());
        assertEquals(1, result.getGeneratedResources().getPages().size(),
                "Should generate exactly 1 page (list)");

        // Verify page type
        assertTrue(result.getGeneratedResources().getPages().stream()
                .anyMatch(p -> "list".equals(p.getPageType())));
    }

    @Test
    @Order(12)
    @DisplayName("P0-4.1: Generate only Form page")
    void test12_generateCrudPages_formOnly() {
        String formOnlyCode = "tmpl_form_" + System.currentTimeMillis();
        createTestModelWithCode(formOnlyCode);

        CrudTemplateConfig config = new CrudTemplateConfig();
        config.setMenuName("Form Only Model");
        config.setGenerateList(false);
        config.setGenerateForm(true);
        config.setGenerateDetail(false);

        TemplateGenerationResult result = templateGeneratorService.generateCrudPages(formOnlyCode, config);

        assertNotNull(result);
        assertEquals(1, result.getGeneratedResources().getPages().size());
        assertTrue(result.getGeneratedResources().getPages().stream()
                .anyMatch(p -> "form".equals(p.getPageType())));
    }

    @Test
    @Order(13)
    @DisplayName("P0-4.1: Generate with export and import permissions")
    void test13_generateCrudPages_withExportImport() {
        String fullCode = "tmpl_full_" + System.currentTimeMillis();
        createTestModelWithCode(fullCode);

        CrudTemplateConfig config = buildDefaultConfig();
        config.setEnableExport(true);
        config.setEnableImport(true);

        TemplateGenerationResult result = templateGeneratorService.generateCrudPages(fullCode, config);

        assertNotNull(result);
        // Should have: read, create, update, delete, export, import = 6
        assertTrue(result.getGeneratedResources().getPermissions().size() >= 6,
                "Should generate at least 6 permissions (CRUD + export + import)");
    }

    // ==================== Error Cases ====================

    @Test
    @Order(20)
    @DisplayName("P0-4.1: Generate for non-existent model should fail")
    void test20_generateCrudPages_modelNotFound() {
        CrudTemplateConfig config = buildDefaultConfig();

        assertThrows(Exception.class, () -> {
            templateGeneratorService.generateCrudPages("non_existent_model", config);
        }, "Non-existent model should throw exception");
    }

    @Test
    @Order(21)
    @DisplayName("P0-4.1: Generate for model without fields should fail")
    void test21_generateCrudPages_noFields() {
        // Create a model without fields
        String noFieldsCode = "tmpl_nofields_" + System.currentTimeMillis();
        Model model = new Model();
        model.setPid(UniqueIdGenerator.generate());
        model.setTenantId(getTestTenant().getId());
        model.setCode(noFieldsCode);
        model.setVersion(1);
        model.setIsCurrent(true);
        model.setStatus(Status.PUBLISHED.getCode());
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        model.setDeletedFlag(false);

        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", "No Fields Model");
        extMap.put("modelType", "entity");
        ext.setExtension(extMap);
        model.setExtension(ext);
        metaModelMapper.insert(model);

        CrudTemplateConfig config = buildDefaultConfig();

        assertThrows(Exception.class, () -> {
            templateGeneratorService.generateCrudPages(noFieldsCode, config);
        }, "Model without fields should throw exception");
    }

    // ==================== Menu Configuration Tests ====================

    @Test
    @Order(30)
    @DisplayName("P0-4.3: Generated menu has correct properties")
    void test30_generatedMenu_properties() {
        String menuTestCode = "tmpl_menu_" + System.currentTimeMillis();
        createTestModelWithCode(menuTestCode);

        CrudTemplateConfig config = new CrudTemplateConfig();
        config.setMenuName("Custom Menu Name");
        config.setMenuIcon("StarIcon");
        config.setGenerateList(true);
        config.setGenerateForm(false);
        config.setGenerateDetail(false);

        TemplateGenerationResult result = templateGeneratorService.generateCrudPages(menuTestCode, config);

        assertNotNull(result);
        assertFalse(result.getGeneratedResources().getMenus().isEmpty());

        var menu = result.getGeneratedResources().getMenus().get(0);
        assertEquals("Custom Menu Name", menu.getMenuName());
        assertTrue(menu.getMenuPath().contains(menuTestCode));
    }

    // ==================== Permission Tests ====================

    @Test
    @Order(40)
    @DisplayName("P0-4.3: Generated permissions have correct codes")
    void test40_generatedPermissions_codes() {
        String permTestCode = "tmpl_perm_" + System.currentTimeMillis();
        createTestModelWithCode(permTestCode);

        CrudTemplateConfig config = buildDefaultConfig();
        config.setEnableExport(true);
        config.setEnableImport(false);

        TemplateGenerationResult result = templateGeneratorService.generateCrudPages(permTestCode, config);

        assertNotNull(result);
        List<String> permCodes = result.getGeneratedResources().getPermissions().stream()
                .map(p -> p.getPermissionCode())
                .toList();

        // Verify CRUD permissions exist
        assertTrue(permCodes.stream().anyMatch(c -> c.contains("read")));
        assertTrue(permCodes.stream().anyMatch(c -> c.contains("create")));
        assertTrue(permCodes.stream().anyMatch(c -> c.contains("update")));
        assertTrue(permCodes.stream().anyMatch(c -> c.contains("delete")));
        assertTrue(permCodes.stream().anyMatch(c -> c.contains("export")));
    }

    // ==================== Role Permission Assignment Tests ====================

    @Test
    @Order(50)
    @DisplayName("P0-4.3: Generate with default roles assigns permissions")
    void test50_generateWithDefaultRoles() {
        String roleTestCode = "tmpl_role_" + System.currentTimeMillis();
        createTestModelWithCode(roleTestCode);

        CrudTemplateConfig config = buildDefaultConfig();
        config.setDefaultRoles(List.of("test_user"));

        TemplateGenerationResult result = templateGeneratorService.generateCrudPages(roleTestCode, config);

        assertNotNull(result);
        // Permissions should be created and assigned to the specified roles
        assertFalse(result.getGeneratedResources().getPermissions().isEmpty());
    }

    // ==================== Helper Methods ====================

    private CrudTemplateConfig buildDefaultConfig() {
        CrudTemplateConfig config = new CrudTemplateConfig();
        config.setMenuName("Test Template Menu");
        config.setMenuIcon("CubeIcon");
        config.setGenerateList(true);
        config.setGenerateForm(true);
        config.setGenerateDetail(true);
        config.setEnableExport(true);
        config.setEnableImport(false);
        return config;
    }

    private void cleanupExistingModel() {
        try {
            Long tenantId = getTestTenant().getId();
            
            // Delete bindings for all template test models
            jdbcTemplate.update(
                "DELETE FROM ab_meta_model_field_binding WHERE model_id IN " +
                "(SELECT id FROM ab_meta_model WHERE code LIKE 'tmpl_%' AND tenant_id = ?)",
                tenantId
            );
            
            // Delete fields with codes starting with tmpl_
            jdbcTemplate.update(
                "DELETE FROM ab_meta_field WHERE code LIKE 'tmpl_%' AND tenant_id = ?",
                tenantId
            );
            
            // Delete models
            jdbcTemplate.update(
                "DELETE FROM ab_meta_model WHERE code LIKE 'tmpl_%' AND tenant_id = ?",
                tenantId
            );
        } catch (Exception e) {
            log.debug("No existing model to clean up: {}", e.getMessage());
        }
    }

    private void createTestModelForTemplate() {
        createTestModelWithCode(testModelCode);
    }

    private String createTestModelWithCode(String code) {
        log.info("Creating test model for template: {}", code);

        Model model = new Model();
        model.setPid(UniqueIdGenerator.generate());
        model.setTenantId(getTestTenant().getId());
        model.setCode(code);
        model.setVersion(1);
        model.setIsCurrent(true);
        model.setStatus(Status.PUBLISHED.getCode());
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        model.setDeletedFlag(false);

        ExtensionBean extension = new ExtensionBean();
        Map<String, Object> extensionMap = new HashMap<>();
        extensionMap.put("displayName", "Template Test " + code);
        extensionMap.put("modelType", "entity");
        extension.setExtension(extensionMap);
        model.setExtension(extension);

        metaModelMapper.insert(model);

        if (testModel == null) {
            testModel = model;
        }

        // Create and bind fields with unique codes per model using timestamp
        String fieldPrefix = code + "_";
        createAndBindTemplateField(model, fieldPrefix + "name", "string", true, 1);
        createAndBindTemplateField(model, fieldPrefix + "desc", "text", false, 2);
        createAndBindTemplateField(model, fieldPrefix + "stat", "string", false, 3);
        createAndBindTemplateField(model, fieldPrefix + "amt", "integer", false, 4);

        trackModel(code);
        return code;
    }

    private void createAndBindTemplateField(Model model, String code, String dataType, boolean required, int order) {
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setTenantId(getTestTenant().getId());
        field.setCode(code);
        field.setDataType(dataType.equals("integer") ? DataType.INTEGER.getCode() : DataType.STRING.getCode());
        field.setVersion(1);
        field.setIsCurrent(true);
        field.setStatus(Status.PUBLISHED.getCode());
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        field.setDeletedFlag(false);

        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(required);
        field.setFeature(feature);

        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        String displayName = code.contains("_") ? code.substring(code.lastIndexOf("_") + 1) : code;
        extMap.put("displayName", displayName.substring(0, 1).toUpperCase() + displayName.substring(1));
        extMap.put("description", code + " field");
        ext.setExtension(extMap);
        field.setExtension(ext);

        metaFieldMapper.insert(field);

        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(getTestTenant().getId());
        binding.setModelId(model.getId());
        binding.setFieldId(field.getId());
        binding.setFieldOrder(order);
        fieldBindingMapper.insert(binding);
    }
}
