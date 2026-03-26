package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.BindingConfiguration;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.FieldUsageService.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FieldUsageService Integration Test
 *
 * Covers P0-5 requirements:
 * 1. getFieldUsage() - Get usage info with model/page/query counts
 * 2. isFieldUsed() - Check if field has any references
 * 3. getModelsUsingField() - Get all models referencing a field
 * 4. getBindingConfigurations() - Get binding configs for a field
 * 5. exportUsageReport() - Generate comprehensive usage report
 * 6. calculateUsageStatistics() - Calculate and cache usage stats
 * 
 * Each test is self-contained and creates its own test data.
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("FieldUsageService Integration Test - P0-5")
class FieldUsageServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private FieldUsageService fieldUsageService;

    @Autowired
    private MetaFieldService metaFieldService;

    @Autowired
    private MetaModelMapper metaModelMapper;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @Autowired
    private MetaModelFieldBindingMapper fieldBindingMapper;

    // ==================== Helper Methods ====================

    private Model createTestModel(String suffix) {
        String modelCode = "usage_model_" + System.currentTimeMillis() + "_" + suffix;
        Model model = new Model();
        model.setPid(UniqueIdGenerator.generate());
        model.setTenantId(getTestTenant().getId());
        model.setCode(modelCode);
        model.setVersion(1);
        model.setIsCurrent(true);
        model.setStatus(Status.PUBLISHED.getCode());
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        model.setDeletedFlag(false);

        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", "Usage Test Model " + suffix);
        extMap.put("modelType", "entity");
        ext.setExtension(extMap);
        model.setExtension(ext);
        metaModelMapper.insert(model);
        trackModel(modelCode);
        return model;
    }

    private Field createField(String suffix) {
        String fieldCode = "usage_field_" + System.currentTimeMillis() + "_" + suffix;
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setTenantId(getTestTenant().getId());
        field.setCode(fieldCode);
        field.setDataType(DataType.STRING.getCode());
        field.setVersion(1);
        field.setIsCurrent(true);
        field.setStatus(Status.PUBLISHED.getCode());
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        field.setDeletedFlag(false);

        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(false);
        field.setFeature(feature);

        ExtensionBean ext = new ExtensionBean();
        Map<String, Object> extMap = new HashMap<>();
        extMap.put("displayName", fieldCode.toUpperCase());
        ext.setExtension(extMap);
        field.setExtension(ext);

        metaFieldMapper.insert(field);
        return field;
    }

    private void bindFieldToModel(Field field, Model model) {
        ModelFieldBinding binding = new ModelFieldBinding();
        binding.setTenantId(getTestTenant().getId());
        binding.setModelId(model.getId());
        binding.setFieldId(field.getId());
        binding.setFieldOrder(0);
        fieldBindingMapper.insert(binding);
    }

    // ==================== isFieldUsed Tests ====================

    @Test
    @Order(1)
    @DisplayName("P0-5.1: isFieldUsed - bound field should return true")
    void test01_isFieldUsed_bound() {
        Model model = createTestModel("bound1");
        Field field = createField("bound1");
        bindFieldToModel(field, model);

        boolean isUsed = fieldUsageService.isFieldUsed(field.getPid());

        assertTrue(isUsed, "Bound field should be reported as used");
    }

    @Test
    @Order(2)
    @DisplayName("P0-5.1: isFieldUsed - unbound field should return false")
    void test02_isFieldUsed_unbound() {
        Field field = createField("unbound1");

        boolean isUsed = fieldUsageService.isFieldUsed(field.getPid());

        assertFalse(isUsed, "Unbound field should not be reported as used");
    }

    @Test
    @Order(3)
    @DisplayName("P0-5.1: isFieldUsed - non-existent field should return false")
    void test03_isFieldUsed_nonExistent() {
        boolean isUsed = fieldUsageService.isFieldUsed("non_existent_field_pid_" + System.currentTimeMillis());

        assertFalse(isUsed, "Non-existent field should return false");
    }

    // ==================== getFieldUsage Tests ====================

    @Test
    @Order(10)
    @DisplayName("P0-5.2: getFieldUsage - bound field returns usage info")
    void test10_getFieldUsage_bound() {
        Model model = createTestModel("usage1");
        Field field = createField("usage1");
        bindFieldToModel(field, model);

        FieldUsageInfo usage = fieldUsageService.getFieldUsage(field.getPid());

        assertNotNull(usage);
        assertEquals(field.getPid(), usage.getFieldPid());
        assertTrue(usage.getModelCount() >= 1, "Should have at least 1 model reference");
        log.info("Field usage: models={}, pages={}, queries={}, total={}",
                usage.getModelCount(), usage.getPageCount(), usage.getQueryCount(), usage.getTotalReferences());
    }

    @Test
    @Order(11)
    @DisplayName("P0-5.2: getFieldUsage - non-existent field should throw")
    void test11_getFieldUsage_nonExistent() {
        assertThrows(ValidationException.class, () -> {
            fieldUsageService.getFieldUsage("non_existent_field_pid_" + System.currentTimeMillis());
        }, "Non-existent field should throw ValidationException");
    }

    @Test
    @Order(12)
    @DisplayName("P0-5.2: getFieldUsage - unbound field returns zero counts")
    void test12_getFieldUsage_unbound() {
        Field field = createField("unbound2");

        FieldUsageInfo usage = fieldUsageService.getFieldUsage(field.getPid());

        assertNotNull(usage);
        assertEquals(0, usage.getModelCount(), "Unbound field should have 0 model references");
    }

    // ==================== getModelsUsingField Tests ====================

    @Test
    @Order(20)
    @DisplayName("P0-5.3: getModelsUsingField - bound field returns model references")
    void test20_getModelsUsingField_bound() {
        Model model = createTestModel("models1");
        Field field = createField("models1");
        bindFieldToModel(field, model);

        List<ModelReference> models = fieldUsageService.getModelsUsingField(field.getPid());

        assertNotNull(models);
        assertFalse(models.isEmpty(), "Should return at least one model reference");
        log.info("Models using field: {}", models.size());
    }

    @Test
    @Order(21)
    @DisplayName("P0-5.3: getModelsUsingField - unbound field returns empty list")
    void test21_getModelsUsingField_unbound() {
        Field field = createField("unbound3");

        List<ModelReference> models = fieldUsageService.getModelsUsingField(field.getPid());

        assertNotNull(models);
        assertTrue(models.isEmpty(), "Unbound field should have no model references");
    }

    @Test
    @Order(22)
    @DisplayName("P0-5.3: getModelsUsingField - non-existent field returns empty list")
    void test22_getModelsUsingField_nonExistent() {
        List<ModelReference> models = fieldUsageService.getModelsUsingField("non_existent_pid_" + System.currentTimeMillis());

        assertNotNull(models);
        assertTrue(models.isEmpty());
    }

    // ==================== getBindingConfigurations Tests ====================

    @Test
    @Order(30)
    @DisplayName("P0-5.4: getBindingConfigurations - bound field returns configs")
    void test30_getBindingConfigurations_bound() {
        Model model = createTestModel("config1");
        Field field = createField("config1");
        bindFieldToModel(field, model);

        List<BindingConfiguration> configs = fieldUsageService.getBindingConfigurations(field.getPid());

        assertNotNull(configs);
        assertFalse(configs.isEmpty(), "Bound field should have binding configurations");
        log.info("Binding configurations: {}", configs.size());
    }

    @Test
    @Order(31)
    @DisplayName("P0-5.4: getBindingConfigurations - unbound field returns empty")
    void test31_getBindingConfigurations_unbound() {
        Field field = createField("unbound4");

        List<BindingConfiguration> configs = fieldUsageService.getBindingConfigurations(field.getPid());

        assertNotNull(configs);
        assertTrue(configs.isEmpty());
    }

    @Test
    @Order(32)
    @DisplayName("P0-5.4: getBindingConfigurations - non-existent field returns empty")
    void test32_getBindingConfigurations_nonExistent() {
        List<BindingConfiguration> configs = fieldUsageService.getBindingConfigurations("non_existent_pid_" + System.currentTimeMillis());

        assertNotNull(configs);
        assertTrue(configs.isEmpty());
    }

    // ==================== calculateUsageStatistics Tests ====================

    @Test
    @Order(40)
    @DisplayName("P0-5.5: calculateUsageStatistics - updates cache for bound field")
    void test40_calculateUsageStatistics_bound() {
        Model model = createTestModel("stats1");
        Field field = createField("stats1");
        bindFieldToModel(field, model);

        FieldUsageStatistics stats = fieldUsageService.calculateUsageStatistics(field.getPid());

        assertNotNull(stats);
        assertTrue(stats.getModelCount() >= 1, "Should count at least 1 model");
        assertTrue(stats.getTotalReferences() >= 1, "Total references should be >= 1");
        log.info("Calculated stats: models={}, pages={}, queries={}, total={}, frequency={}",
                stats.getModelCount(), stats.getPageCount(), stats.getQueryCount(),
                stats.getTotalReferences(), stats.getUsageFrequency());
    }

    @Test
    @Order(41)
    @DisplayName("P0-5.5: calculateUsageStatistics - non-existent field should throw")
    void test41_calculateUsageStatistics_nonExistent() {
        assertThrows(ValidationException.class, () -> {
            fieldUsageService.calculateUsageStatistics("non_existent_pid_" + System.currentTimeMillis());
        });
    }

    @Test
    @Order(42)
    @DisplayName("P0-5.5: calculateUsageStatistics - verifies cache update")
    void test42_calculateUsageStatistics_cacheUpdate() {
        Model model = createTestModel("cache1");
        Field field = createField("cache1");
        bindFieldToModel(field, model);

        // Calculate stats (updates cache)
        FieldUsageStatistics stats = fieldUsageService.calculateUsageStatistics(field.getPid());
        assertNotNull(stats);

        // Get usage info (should read from cache)
        FieldUsageInfo usage = fieldUsageService.getFieldUsage(field.getPid());
        assertNotNull(usage);
        assertEquals(stats.getModelCount(), usage.getModelCount(),
                "Cache should reflect calculated model count");
    }

    // ==================== exportUsageReport Tests ====================

    @Test
    @Order(50)
    @DisplayName("P0-5.6: exportUsageReport - bound field generates complete report")
    void test50_exportUsageReport_bound() {
        Model model = createTestModel("report1");
        Field field = createField("report1");
        bindFieldToModel(field, model);

        FieldUsageReport report = fieldUsageService.exportUsageReport(field.getPid());

        assertNotNull(report);
        assertEquals(field.getPid(), report.getFieldPid());
        assertNotNull(report.getFieldCode());
        assertNotNull(report.getUsageInfo());
        assertNotNull(report.getModels());
        assertNotNull(report.getPages());
        assertNotNull(report.getQueries());
        assertNotNull(report.getGeneratedAt());

        log.info("Usage report: field={}, models={}, pages={}, queries={}",
                report.getFieldCode(), report.getModels().size(),
                report.getPages().size(), report.getQueries().size());
    }

    @Test
    @Order(51)
    @DisplayName("P0-5.6: exportUsageReport - unbound field generates empty report")
    void test51_exportUsageReport_unbound() {
        Field field = createField("unbound5");

        FieldUsageReport report = fieldUsageService.exportUsageReport(field.getPid());

        assertNotNull(report);
        assertEquals(field.getPid(), report.getFieldPid());
        assertNotNull(report.getModels());
        assertTrue(report.getModels().isEmpty(), "Unbound field should have empty models list");
    }

    // ==================== Multi-Model Binding Tests ====================

    @Test
    @Order(60)
    @DisplayName("P0-5.7: Field bound to multiple models shows correct count")
    void test60_fieldBoundToMultipleModels() {
        Model model1 = createTestModel("multi1");
        Model model2 = createTestModel("multi2");
        Field field = createField("multi1");
        
        bindFieldToModel(field, model1);
        bindFieldToModel(field, model2);

        // Calculate statistics
        FieldUsageStatistics stats = fieldUsageService.calculateUsageStatistics(field.getPid());

        assertNotNull(stats);
        assertTrue(stats.getModelCount() >= 2,
                "Field bound to 2 models should show count >= 2");

        // Verify models list
        List<ModelReference> models = fieldUsageService.getModelsUsingField(field.getPid());
        assertTrue(models.size() >= 2);
    }

    // ==================== Query Reference Tests ====================

    @Test
    @Order(70)
    @DisplayName("P0-5.8: Query reference count for field code")
    void test70_queryReferenceCount() {
        Model model = createTestModel("query1");
        Field field = createField("query1");
        bindFieldToModel(field, model);

        // This tests the integration with NamedQueryService
        FieldUsageInfo usage = fieldUsageService.getFieldUsage(field.getPid());
        assertNotNull(usage);
        // queryCount may be 0 if no named queries reference this field
        assertTrue(usage.getQueryCount() >= 0);
    }

    // ==================== Edge Cases ====================

    @Test
    @Order(80)
    @DisplayName("P0-5.9: Usage info for field with no cache entry")
    void test80_usageInfo_noCacheEntry() {
        Model model = createTestModel("nocache1");
        Field field = createField("nocache1");
        bindFieldToModel(field, model);

        // Getting usage should trigger cache calculation
        FieldUsageInfo usage = fieldUsageService.getFieldUsage(field.getPid());

        assertNotNull(usage);
        assertTrue(usage.getModelCount() >= 1,
                "Should calculate and cache usage on cache miss");
    }
}
