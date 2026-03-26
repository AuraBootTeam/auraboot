package com.auraboot.framework.integration.meta;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.MetaModelFieldBindingDTO;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.ModelFieldBindingService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Field management integration tests
 * 
 * Tests field sorting, configuration updates, and unbinding functionality
 * using the new ModelFieldBindingService.
 * 
 * @author AuraBoot Team
 * @since 3.0.0
 */
@Slf4j
@Transactional
@Rollback(true)
@DisplayName("Field Management Integration Tests")
public class FieldManagementIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ModelFieldBindingService bindingService;

    @Autowired
    private MetaModelMapper modelMapper;

    @Autowired
    private MetaFieldMapper fieldMapper;

    @Autowired
    private MetaModelFieldBindingMapper bindingMapper;

    private Model testModel;
    private Field testField1;
    private Field testField2;
    private Field testField3;

    @BeforeEach
    void setupTestData() {
        setupTenantContext();
        
        // Create test model directly in database
        testModel = createTestModel("test_model_" + System.currentTimeMillis());
        
        // Create test fields directly in database
        testField1 = createTestField("field1_" + System.currentTimeMillis(), "string");
        testField2 = createTestField("field2_" + System.currentTimeMillis(), "integer");
        testField3 = createTestField("field3_" + System.currentTimeMillis(), "boolean");

        // Bind fields to model using the service
        bindingService.bindFieldToModel(testModel.getPid(), testField1.getPid(), 1, false, false, true);
        bindingService.bindFieldToModel(testModel.getPid(), testField2.getPid(), 2, false, false, true);
        bindingService.bindFieldToModel(testModel.getPid(), testField3.getPid(), 3, false, false, true);
        
        log.info("Test data setup complete: model={}, fields=[{}, {}, {}]", 
            testModel.getCode(), testField1.getCode(), testField2.getCode(), testField3.getCode());
    }

    @Test
    @DisplayName("Test field sorting functionality")
    void testFieldSorting() {
        // Given: Three fields bound to model with initial order 1, 2, 3
        List<ModelFieldBinding> initialBindings = bindingMapper.findByModelId(testModel.getId());
        
        assertEquals(3, initialBindings.size(), "Should have 3 field bindings");
        
        // Verify initial order
        ModelFieldBinding binding1 = findBindingByFieldId(initialBindings, testField1.getId());
        ModelFieldBinding binding2 = findBindingByFieldId(initialBindings, testField2.getId());
        ModelFieldBinding binding3 = findBindingByFieldId(initialBindings, testField3.getId());
        
        assertEquals(1, binding1.getFieldOrder());
        assertEquals(2, binding2.getFieldOrder());
        assertEquals(3, binding3.getFieldOrder());

        // When: Reorder fields (swap field1 and field3)
        Map<String, Integer> newOrder = new HashMap<>();
        newOrder.put(testField1.getPid(), 3);
        newOrder.put(testField3.getPid(), 1);
        
        int updated = bindingService.reorderFields(testModel.getPid(), newOrder);
        
        // Then: Order should be updated
        assertEquals(2, updated, "Should update 2 bindings");
        
        List<ModelFieldBinding> updatedBindings = bindingMapper.findByModelId(testModel.getId());
        ModelFieldBinding updatedBinding1 = findBindingByFieldId(updatedBindings, testField1.getId());
        ModelFieldBinding updatedBinding3 = findBindingByFieldId(updatedBindings, testField3.getId());
        
        assertEquals(3, updatedBinding1.getFieldOrder(), "Field1 should now be at position 3");
        assertEquals(1, updatedBinding3.getFieldOrder(), "Field3 should now be at position 1");
        
        log.info("✓ Field sorting test passed");
    }

    @Test
    @DisplayName("Test field configuration update")
    void testFieldConfigUpdate() {
        // Given: A field bound to model with default config
        ModelFieldBinding initialBinding = bindingMapper.selectByModelAndField(
            testModel.getId(), testField1.getId());
        
        assertFalse(initialBinding.getRequired(), "Initial required should be false");
        assertTrue(initialBinding.getVisible(), "Initial visible should be true");
        assertTrue(initialBinding.getEditable(), "Initial editable should be true");

        // When: Update field configuration
        MetaModelFieldBindingDTO updatedBinding = bindingService.updateFieldConfig(
            testModel.getPid(),
            testField1.getPid(),
            true,   // isRequired
            true,   // isReadonly (editable = false)
            false   // isVisible
        );

        // Then: Configuration should be updated
        assertNotNull(updatedBinding);
        assertTrue(updatedBinding.getRequired(), "Required should be true");
        assertTrue(updatedBinding.getReadonly(), "Readonly should be true");
        assertFalse(updatedBinding.getVisible(), "Visible should be false");
        
        // Verify in database
        ModelFieldBinding dbBinding = bindingMapper.selectByModelAndField(
            testModel.getId(), testField1.getId());
        assertTrue(dbBinding.getRequired());
        assertFalse(dbBinding.getEditable()); // readonly = !editable
        assertFalse(dbBinding.getVisible());
        
        log.info("✓ Field configuration update test passed");
    }

    @Test
    @DisplayName("Test field unbinding")
    void testFieldUnbinding() {
        // Given: Three fields bound to model
        List<ModelFieldBinding> initialBindings = bindingMapper.findByModelId(testModel.getId());
        assertEquals(3, initialBindings.size());

        // When: Unbind field2
        boolean result = bindingService.unbindFieldFromModel(testModel.getPid(), testField2.getPid());

        // Then: Field2 should be unbound
        assertTrue(result, "Unbind should succeed");
        
        List<ModelFieldBinding> remainingBindings = bindingMapper.findByModelId(testModel.getId());
        assertEquals(2, remainingBindings.size(), "Should have 2 remaining bindings");
        
        // Verify field2 is not in the list
        assertNull(findBindingByFieldId(remainingBindings, testField2.getId()), 
            "Field2 should not be in bindings");
        
        // Verify field1 and field3 are still bound
        assertNotNull(findBindingByFieldId(remainingBindings, testField1.getId()));
        assertNotNull(findBindingByFieldId(remainingBindings, testField3.getId()));
        
        log.info("✓ Field unbinding test passed");
    }

    @Test
    @DisplayName("Test field rebinding after unbinding")
    void testFieldRebinding() {
        // Given: Unbind field2
        bindingService.unbindFieldFromModel(testModel.getPid(), testField2.getPid());
        
        List<ModelFieldBinding> afterUnbind = bindingMapper.findByModelId(testModel.getId());
        assertEquals(2, afterUnbind.size());

        // When: Rebind field2 with new configuration
        MetaModelFieldBindingDTO rebinding = bindingService.bindFieldToModel(
            testModel.getPid(),
            testField2.getPid(),
            5,      // new order
            true,   // required
            true,   // readonly
            true    // visible
        );

        // Then: Field2 should be rebound with new config
        assertNotNull(rebinding);
        assertEquals(5, rebinding.getFieldOrder());
        assertTrue(rebinding.getRequired());
        assertTrue(rebinding.getReadonly());
        
        List<ModelFieldBinding> afterRebind = bindingMapper.findByModelId(testModel.getId());
        assertEquals(3, afterRebind.size(), "Should have 3 bindings again");
        
        log.info("✓ Field rebinding test passed");
    }

    // ==================== Helper Methods ====================

    private Model createTestModel(String code) {
        Model model = new Model();
        model.setPid("model_pid_" + System.currentTimeMillis());
        model.setTenantId(getTestTenant().getId());
        model.setCode(code);
        
        Map<String, Object> extensionData = new HashMap<>();
        extensionData.put("displayName", "Test Model");
        extensionData.put("description", "Model for testing");
        
        com.auraboot.framework.meta.entity.payload.ExtensionBean extension = 
            new com.auraboot.framework.meta.entity.payload.ExtensionBean();
        extension.setExtension(extensionData);
        model.setExtension(extension);
        
        model.setVersion(1);
        model.setIsCurrent(true);
        model.setStatus("published");
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        model.setDeletedFlag(false);
        
        modelMapper.insert(model);
        log.info("Created test model: code={}, pid={}, id={}", model.getCode(), model.getPid(), model.getId());
        
        return model;
    }

    private Field createTestField(String code, String dataType) {
        Field field = new Field();
        field.setPid("field_pid_" + System.currentTimeMillis() + "_" + code);
        field.setTenantId(getTestTenant().getId());
        field.setCode(code);
        field.setDataType(dataType);
        
        Map<String, Object> extensionData = new HashMap<>();
        extensionData.put("displayName", "Test Field " + code);
        
        com.auraboot.framework.meta.entity.payload.ExtensionBean extension = 
            new com.auraboot.framework.meta.entity.payload.ExtensionBean();
        extension.setExtension(extensionData);
        field.setExtension(extension);
        
        com.auraboot.framework.meta.entity.payload.FieldFeatureBean feature = 
            new com.auraboot.framework.meta.entity.payload.FieldFeatureBean();
        feature.setRequired(false);
        feature.setUnique(false);
        field.setFeature(feature);
        
        field.setVersion(1);
        field.setIsCurrent(true);
        field.setStatus("published");
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        field.setDeletedFlag(false);
        
        fieldMapper.insert(field);
        log.info("Created test field: code={}, pid={}, id={}", field.getCode(), field.getPid(), field.getId());
        
        return field;
    }

    private ModelFieldBinding findBindingByFieldId(List<ModelFieldBinding> bindings, Long fieldId) {
        return bindings.stream()
            .filter(b -> b.getFieldId().equals(fieldId))
            .findFirst()
            .orElse(null);
    }
}
