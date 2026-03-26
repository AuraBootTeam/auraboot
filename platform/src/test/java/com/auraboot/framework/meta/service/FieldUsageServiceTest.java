package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.constant.DataType;
import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.dto.BindingConfiguration;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.FieldUsageService.FieldUsageInfo;
import com.auraboot.framework.meta.service.FieldUsageService.FieldUsageReport;
import com.auraboot.framework.meta.service.FieldUsageService.FieldUsageStatistics;
import com.auraboot.framework.meta.service.FieldUsageService.ModelReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FieldUsageService unit test
 * Tests field usage tracking and reporting capabilities
 * 
 * Each test creates its own test data to be self-contained.
 */
@DisplayName("FieldUsageService Test")
class FieldUsageServiceTest extends BaseIntegrationTest {

    @Autowired
    private FieldUsageService fieldUsageService;

    @Autowired
    private MetaModelMapper metaModelMapper;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @Autowired
    private MetaModelFieldBindingMapper fieldBindingMapper;

    // ==================== Helper Methods ====================

    private Model createTestModel(String suffix) {
        String modelCode = "usage_test_model_" + System.currentTimeMillis() + "_" + suffix;
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
        String fieldCode = "usage_test_field_" + System.currentTimeMillis() + "_" + suffix;
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

    // ==================== Tests ====================

    @Test
    @DisplayName("Test get field usage information")
    void testGetFieldUsage() {
        // Given - create real field with binding
        Model model = createTestModel("usage1");
        Field field = createField("usage1");
        bindFieldToModel(field, model);

        // When
        FieldUsageInfo usageInfo = fieldUsageService.getFieldUsage(field.getPid());

        // Then
        assertNotNull(usageInfo);
        assertEquals(field.getPid(), usageInfo.getFieldPid());
        assertTrue(usageInfo.getModelCount() >= 1);
    }

    @Test
    @DisplayName("Test check if field is used")
    void testIsFieldUsed() {
        // Given - create real field with binding
        Model model = createTestModel("used1");
        Field field = createField("used1");
        bindFieldToModel(field, model);

        // When
        boolean isUsed = fieldUsageService.isFieldUsed(field.getPid());

        // Then
        assertTrue(isUsed, "Bound field should be reported as used");
    }

    @Test
    @DisplayName("Test get models using field")
    void testGetModelsUsingField() {
        // Given - create real field with binding
        Model model = createTestModel("models1");
        Field field = createField("models1");
        bindFieldToModel(field, model);

        // When
        List<ModelReference> models = fieldUsageService.getModelsUsingField(field.getPid());

        // Then
        assertNotNull(models);
        assertFalse(models.isEmpty(), "Should return at least one model reference");
    }

    @Test
    @DisplayName("Test get binding configurations")
    void testGetBindingConfigurations() {
        // Given - create real field with binding
        Model model = createTestModel("config1");
        Field field = createField("config1");
        bindFieldToModel(field, model);

        // When
        List<BindingConfiguration> bindings = fieldUsageService.getBindingConfigurations(field.getPid());

        // Then
        assertNotNull(bindings);
        assertFalse(bindings.isEmpty(), "Should return at least one binding configuration");
    }

    @Test
    @DisplayName("Test export usage report")
    void testExportUsageReport() {
        // Given - create real field with binding
        Model model = createTestModel("report1");
        Field field = createField("report1");
        bindFieldToModel(field, model);

        // When
        FieldUsageReport report = fieldUsageService.exportUsageReport(field.getPid());

        // Then
        assertNotNull(report);
        assertEquals(field.getPid(), report.getFieldPid());
        assertNotNull(report.getUsageInfo());
        assertNotNull(report.getModels());
        assertNotNull(report.getGeneratedAt());
    }

    @Test
    @DisplayName("Test calculate usage statistics")
    void testCalculateUsageStatistics() {
        // Given - create real field with binding
        Model model = createTestModel("stats1");
        Field field = createField("stats1");
        bindFieldToModel(field, model);

        // When
        FieldUsageStatistics statistics = fieldUsageService.calculateUsageStatistics(field.getPid());

        // Then
        assertNotNull(statistics);
        assertEquals(field.getPid(), statistics.getFieldPid());
        assertTrue(statistics.getModelCount() >= 1);
        assertNotNull(statistics.getCalculatedAt());
    }

    @Test
    @DisplayName("Test refresh usage cache for single field")
    void testRefreshUsageCache() {
        // Given - create real field with binding
        Model model = createTestModel("refresh1");
        Field field = createField("refresh1");
        bindFieldToModel(field, model);

        // When & Then - Should not throw exception
        assertDoesNotThrow(() -> fieldUsageService.refreshUsageCache(field.getPid()));
    }

    @Test
    @DisplayName("Test refresh all usage cache")
    void testRefreshAllUsageCache() {
        // When & Then - Should not throw exception
        assertDoesNotThrow(() -> fieldUsageService.refreshAllUsageCache());
    }

    @Test
    @DisplayName("Test field usage info structure")
    void testFieldUsageInfoStructure() {
        // Given
        FieldUsageInfo info = new FieldUsageInfo();
        
        // When
        info.setFieldPid("test-pid");
        info.setFieldCode("test_code");
        info.setModelCount(5);
        info.setPageCount(3);
        info.setQueryCount(2);
        info.setTotalReferences(10);
        info.setCoreField(false);

        // Then
        assertEquals("test-pid", info.getFieldPid());
        assertEquals("test_code", info.getFieldCode());
        assertEquals(5, info.getModelCount());
        assertEquals(3, info.getPageCount());
        assertEquals(2, info.getQueryCount());
        assertEquals(10, info.getTotalReferences());
        assertFalse(info.isCoreField());
    }

    @Test
    @DisplayName("Test model reference structure")
    void testModelReferenceStructure() {
        // Given
        ModelReference ref = new ModelReference();
        
        // When
        ref.setModelPid("model-pid");
        ref.setModelCode("model_code");
        ref.setModelDisplayName("Model Display Name");

        // Then
        assertEquals("model-pid", ref.getModelPid());
        assertEquals("model_code", ref.getModelCode());
        assertEquals("Model Display Name", ref.getModelDisplayName());
    }

    @Test
    @DisplayName("Test field usage report structure")
    void testFieldUsageReportStructure() {
        // Given
        FieldUsageReport report = new FieldUsageReport();
        FieldUsageInfo info = new FieldUsageInfo();
        info.setFieldPid("test-pid");
        
        // When
        report.setFieldPid("test-pid");
        report.setFieldCode("test_code");
        report.setUsageInfo(info);

        // Then
        assertEquals("test-pid", report.getFieldPid());
        assertEquals("test_code", report.getFieldCode());
        assertNotNull(report.getUsageInfo());
        assertEquals("test-pid", report.getUsageInfo().getFieldPid());
    }

    @Test
    @DisplayName("Test field usage statistics structure")
    void testFieldUsageStatisticsStructure() {
        // Given
        FieldUsageStatistics stats = new FieldUsageStatistics();
        
        // When
        stats.setFieldPid("test-pid");
        stats.setModelCount(5);
        stats.setPageCount(3);
        stats.setQueryCount(2);
        stats.setTotalReferences(10);

        // Then
        assertEquals("test-pid", stats.getFieldPid());
        assertEquals(5, stats.getModelCount());
        assertEquals(3, stats.getPageCount());
        assertEquals(2, stats.getQueryCount());
        assertEquals(10, stats.getTotalReferences());
    }
}
