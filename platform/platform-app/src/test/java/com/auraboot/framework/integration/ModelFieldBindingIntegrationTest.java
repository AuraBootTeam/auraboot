package com.auraboot.framework.integration;

import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelFieldBindingDTO;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.service.ModelFieldBindingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Model-Field Binding Service Integration Test
 * 
 * Tests the ModelFieldBindingService which resolves the circular dependency
 * between MetaFieldService and MetaModelService.
 * 
 * @author AuraBoot Framework
 * @since 3.0.0
 */
@DisplayName("Model-Field Binding Integration Test")
public class ModelFieldBindingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MetaModelMapper metaModelMapper;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @Autowired
    private MetaModelFieldBindingMapper fieldBindingMapper;

    @Autowired
    private ModelFieldBindingService bindingService;

    private Model testModel;
    private Field testField1;
    private Field testField2;

    @BeforeEach
    void setUp() {
        setupTenantContext();
        createTestData();
    }

    private void createTestData() {
        // Create test model
        testModel = new Model();
        testModel.setPid("test_model_" + System.currentTimeMillis());
        testModel.setTenantId(getTestTenant().getId());
        testModel.setCode("TestModel");
        
        Map<String, Object> extensionData = new HashMap<>();
        extensionData.put("displayName", "Test Model");
        extensionData.put("description", "Model for testing");
        extensionData.put("modelType", "entity");
        
        com.auraboot.framework.meta.entity.payload.ExtensionBean extension = 
            new com.auraboot.framework.meta.entity.payload.ExtensionBean();
        extension.setExtension(extensionData);
        testModel.setExtension(extension);
        
        testModel.setVersion(1);
        testModel.setIsCurrent(true);
        testModel.setStatus("published");
        testModel.setCreatedAt(Instant.now());
        testModel.setUpdatedAt(Instant.now());
        testModel.setDeletedFlag(false);
        
        metaModelMapper.insert(testModel);

        // Create test field 1
        testField1 = new Field();
        testField1.setPid("test_field1_" + System.currentTimeMillis());
        testField1.setTenantId(getTestTenant().getId());
        testField1.setCode("testField1");
        testField1.setDataType("string");
        
        Map<String, Object> field1Extension = new HashMap<>();
        field1Extension.put("displayName", "Test Field 1");
        field1Extension.put("description", "First test field");
        
        com.auraboot.framework.meta.entity.payload.ExtensionBean field1ExtensionBean = 
            new com.auraboot.framework.meta.entity.payload.ExtensionBean();
        field1ExtensionBean.setExtension(field1Extension);
        testField1.setExtension(field1ExtensionBean);
        
        com.auraboot.framework.meta.entity.payload.FieldFeatureBean field1Feature = 
            new com.auraboot.framework.meta.entity.payload.FieldFeatureBean();
        field1Feature.setRequired(false);
        field1Feature.setUnique(false);
        testField1.setFeature(field1Feature);
        
        testField1.setVersion(1);
        testField1.setIsCurrent(true);
        testField1.setStatus("published");
        testField1.setCreatedAt(Instant.now());
        testField1.setUpdatedAt(Instant.now());
        testField1.setDeletedFlag(false);
        
        metaFieldMapper.insert(testField1);

        // Create test field 2
        testField2 = new Field();
        testField2.setPid("test_field2_" + System.currentTimeMillis());
        testField2.setTenantId(getTestTenant().getId());
        testField2.setCode("testField2");
        testField2.setDataType("integer");
        
        Map<String, Object> field2Extension = new HashMap<>();
        field2Extension.put("displayName", "Test Field 2");
        field2Extension.put("description", "Second test field");
        
        com.auraboot.framework.meta.entity.payload.ExtensionBean field2ExtensionBean = 
            new com.auraboot.framework.meta.entity.payload.ExtensionBean();
        field2ExtensionBean.setExtension(field2Extension);
        testField2.setExtension(field2ExtensionBean);
        
        com.auraboot.framework.meta.entity.payload.FieldFeatureBean field2Feature = 
            new com.auraboot.framework.meta.entity.payload.FieldFeatureBean();
        field2Feature.setRequired(true);
        field2Feature.setUnique(false);
        testField2.setFeature(field2Feature);
        
        testField2.setVersion(1);
        testField2.setIsCurrent(true);
        testField2.setStatus("published");
        testField2.setCreatedAt(Instant.now());
        testField2.setUpdatedAt(Instant.now());
        testField2.setDeletedFlag(false);
        
        metaFieldMapper.insert(testField2);
    }

    @Test
    @Transactional
    @DisplayName("Test bind field to model")
    void testBindFieldToModel() {
        // Execute binding
        MetaModelFieldBindingDTO binding = bindingService.bindFieldToModel(
            testModel.getPid(),
            testField1.getPid(),
            1,
            true,
            false,
            true
        );

        // Verify binding result
        assertNotNull(binding);
        assertEquals(testModel.getId(), binding.getModelId());
        assertEquals(testField1.getId(), binding.getFieldId());
        assertEquals(1, binding.getFieldOrder());
        assertTrue(binding.getRequired());
        assertTrue(binding.getVisible());
        assertFalse(binding.getReadonly());

        // Verify database binding
        ModelFieldBinding dbBinding = fieldBindingMapper.selectByModelAndField(
            testModel.getId(), testField1.getId());
        assertNotNull(dbBinding);
        assertEquals(binding.getId(), dbBinding.getId());
    }

    @Test
    @Transactional
    @DisplayName("Test duplicate binding returns existing")
    void testBindFieldToModelDuplicate() {
        // First binding
        MetaModelFieldBindingDTO binding1 = bindingService.bindFieldToModel(
            testModel.getPid(),
            testField1.getPid(),
            1,
            true,
            false,
            true
        );

        // Second binding should return existing
        MetaModelFieldBindingDTO binding2 = bindingService.bindFieldToModel(
            testModel.getPid(),
            testField1.getPid(),
            2,
            false,
            true,
            false
        );

        // Should return the same binding
        assertEquals(binding1.getId(), binding2.getId());
    }

    @Test
    @Transactional
    @DisplayName("Test unbind field from model")
    void testUnbindFieldFromModel() {
        // First bind
        bindingService.bindFieldToModel(
            testModel.getPid(),
            testField1.getPid(),
            1,
            true,
            false,
            true
        );

        // Execute unbind
        boolean success = bindingService.unbindFieldFromModel(
            testModel.getPid(), testField1.getPid());
        assertTrue(success);

        // Verify binding is deleted
        ModelFieldBinding binding = fieldBindingMapper.selectByModelAndField(
            testModel.getId(), testField1.getId());
        assertNull(binding);
    }

    @Test
    @Transactional
    @DisplayName("Test unbind non-existent field")
    void testUnbindNonExistentField() {
        boolean success = bindingService.unbindFieldFromModel(
            testModel.getPid(), testField1.getPid());
        assertFalse(success);
    }

    @Test
    @Transactional
    @DisplayName("Test get model fields")
    void testGetModelFields() {
        // Bind two fields
        bindTestFields();

        // Get field list
        List<MetaFieldDTO> fields = bindingService.getModelFields(testModel.getPid());
        
        assertNotNull(fields);
        assertEquals(2, fields.size());
    }

    @Test
    @Transactional
    @DisplayName("Test get model bindings")
    void testGetModelBindings() {
        // Bind two fields
        bindTestFields();

        // Get bindings
        List<MetaModelFieldBindingDTO> bindings = bindingService.getModelBindings(testModel.getPid());
        
        assertNotNull(bindings);
        assertEquals(2, bindings.size());
    }

    @Test
    @Transactional
    @DisplayName("Test update field config")
    void testUpdateFieldConfig() {
        // First bind
        bindingService.bindFieldToModel(
            testModel.getPid(),
            testField1.getPid(),
            1,
            true,
            false,
            true
        );

        // Update config
        MetaModelFieldBindingDTO updatedBinding = bindingService.updateFieldConfig(
            testModel.getPid(),
            testField1.getPid(),
            false,  // isRequired
            true,   // isReadonly
            false   // isVisible
        );
        
        // Verify update result
        assertNotNull(updatedBinding);
        assertFalse(updatedBinding.getRequired());
        assertTrue(updatedBinding.getReadonly());
        assertFalse(updatedBinding.getVisible());
    }

    @Test
    @Transactional
    @DisplayName("Test reorder fields")
    void testReorderFields() {
        // Bind two fields
        bindTestFields();

        // Prepare order updates
        Map<String, Integer> fieldOrders = new HashMap<>();
        fieldOrders.put(testField1.getPid(), 10);
        fieldOrders.put(testField2.getPid(), 5);

        // Execute reorder
        int updated = bindingService.reorderFields(testModel.getPid(), fieldOrders);
        
        // Verify update result
        assertEquals(2, updated);

        // Verify order update
        ModelFieldBinding binding1 = fieldBindingMapper.selectByModelAndField(
            testModel.getId(), testField1.getId());
        ModelFieldBinding binding2 = fieldBindingMapper.selectByModelAndField(
            testModel.getId(), testField2.getId());
        
        assertEquals(10, binding1.getFieldOrder());
        assertEquals(5, binding2.getFieldOrder());
    }

    @Test
    @Transactional
    @DisplayName("Test batch bind fields")
    void testBatchBindFields() {
        // Execute batch bind
        int bound = bindingService.batchBindFields(
            testModel.getPid(),
            List.of(testField1.getPid(), testField2.getPid())
        );
        
        // Verify result
        assertEquals(2, bound);

        // Verify bindings exist
        List<MetaModelFieldBindingDTO> bindings = bindingService.getModelBindings(testModel.getPid());
        assertEquals(2, bindings.size());
    }

    @Test
    @Transactional
    @DisplayName("Test bind to non-existent model")
    void testBindFieldToNonExistentModel() {
        assertThrows(Exception.class, () -> {
            bindingService.bindFieldToModel(
                "non_existent_model_pid",
                testField1.getPid(),
                1,
                true,
                false,
                true
            );
        });
    }

    @Test
    @Transactional
    @DisplayName("Test bind non-existent field")
    void testBindNonExistentFieldToModel() {
        assertThrows(Exception.class, () -> {
            bindingService.bindFieldToModel(
                testModel.getPid(),
                "non_existent_field_pid",
                1,
                true,
                false,
                true
            );
        });
    }

    // ==================== Helper Methods ====================

    private void bindTestFields() {
        // Bind field 1
        bindingService.bindFieldToModel(
            testModel.getPid(),
            testField1.getPid(),
            1,
            true,
            false,
            true
        );

        // Bind field 2
        bindingService.bindFieldToModel(
            testModel.getPid(),
            testField2.getPid(),
            2,
            false,
            false,
            true
        );
    }
}
