package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.exception.ResourceNotFoundException;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.BatchFieldBindingRequest;
import com.auraboot.framework.meta.dto.FieldBindingRequest;
import com.auraboot.framework.meta.dto.MetaModelFieldBindingDTO;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.impl.ModelFieldBindingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ModelFieldBindingService
 * Tests the new field binding with configuration methods
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Model Field Binding Service Tests")
class ModelFieldBindingServiceTest {

    @Mock
    private MetaModelMapper metaModelMapper;

    @Mock
    private MetaFieldMapper metaFieldMapper;

    @Mock
    private MetaModelFieldBindingMapper bindingMapper;

    @InjectMocks
    private ModelFieldBindingServiceImpl bindingService;

    private Model testModel;
    private Field testField;
    private ModelFieldBinding testBinding;

    @BeforeEach
    void setUp() {
        // Setup tenant context
        MetaContext.setCurrentTenantId(1L);

        // Setup test model
        testModel = new Model();
        testModel.setId(100L);
        testModel.setPid("model_pid_001");
        testModel.setCode("test_model");

        // Setup test field
        testField = new Field();
        testField.setId(200L);
        testField.setPid("field_pid_001");
        testField.setCode("test_field");
        testField.setDataType("string");

        // Setup test binding
        testBinding = new ModelFieldBinding();
        testBinding.setId(1L);
        testBinding.setTenantId(1L);
        testBinding.setModelId(100L);
        testBinding.setFieldId(200L);
        testBinding.setFieldOrder(0);
        testBinding.setRequired(false);
        testBinding.setEditable(true);
        testBinding.setVisible(true);
        testBinding.setCreatedAt(Instant.now());
        testBinding.setUpdatedAt(Instant.now());
    }

    @Test
    @DisplayName("Should bind field with full configuration successfully")
    void testBindFieldWithConfig_Success() {
        // Given
        String modelPid = "model_pid_001";
        FieldBindingRequest request = FieldBindingRequest.builder()
                .fieldPid("field_pid_001")
                .aliasCode("custom_alias")
                .required(true)
                .nullable(false)
                .readonly(false)
                .visible(true)
                .editable(true)
                .defaultValue("default_value")
                .dictOverrideCode("dict_001")
                .uiHint("text")
                .validationOverride("{\"maxLength\": 100}")
                .displayConfig("{\"width\": \"100%\"}")
                .remarks("Test field binding")
                .build();

        when(metaModelMapper.findByPid(modelPid)).thenReturn(testModel);
        when(metaFieldMapper.findByPid(request.getFieldPid())).thenReturn(testField);
        when(bindingMapper.selectByModelAndField(testModel.getId(), testField.getId())).thenReturn(null);
        when(bindingMapper.findByModelId(testModel.getId())).thenReturn(Arrays.asList());
        when(bindingMapper.getMaxFieldOrder(testModel.getId())).thenReturn(-1);
        when(bindingMapper.insert(any(ModelFieldBinding.class))).thenReturn(1);

        // When
        MetaModelFieldBindingDTO result = bindingService.bindFieldWithConfig(modelPid, request);

        // Then
        assertNotNull(result);
        assertEquals(testModel.getId(), result.getModelId());
        assertEquals(testField.getId(), result.getFieldId());
        verify(bindingMapper).insert(argThat((ModelFieldBinding binding) ->
            binding.getAliasCode().equals("custom_alias") &&
            binding.getRequired() == true &&
            binding.getEditable() == true &&
            binding.getVisible() == true &&
            binding.getDefaultValue().equals("default_value") &&
            binding.getDictOverrideCode().equals("dict_001") &&
            binding.getUiHint().equals("text") &&
            binding.getValidationOverride().equals("{\"maxLength\": 100}") &&
            binding.getDisplayConfig().equals("{\"width\": \"100%\"}") &&
            binding.getRemarks().equals("Test field binding")
        ));
    }

    @Test
    @DisplayName("Should throw exception when model not found")
    void testBindFieldWithConfig_ModelNotFound() {
        // Given
        String modelPid = "invalid_model";
        FieldBindingRequest request = FieldBindingRequest.builder()
                .fieldPid("field_pid_001")
                .build();

        when(metaModelMapper.findByPid(modelPid)).thenReturn(null);

        // When & Then
        assertThrows(ResourceNotFoundException.class, () -> 
            bindingService.bindFieldWithConfig(modelPid, request)
        );
    }

    @Test
    @DisplayName("Should throw exception when field not found")
    void testBindFieldWithConfig_FieldNotFound() {
        // Given
        String modelPid = "model_pid_001";
        FieldBindingRequest request = FieldBindingRequest.builder()
                .fieldPid("invalid_field")
                .build();

        when(metaModelMapper.findByPid(modelPid)).thenReturn(testModel);
        when(metaFieldMapper.findByPid(request.getFieldPid())).thenReturn(null);

        // When & Then
        assertThrows(ResourceNotFoundException.class, () -> 
            bindingService.bindFieldWithConfig(modelPid, request)
        );
    }

    @Test
    @DisplayName("Should throw exception when field already bound")
    void testBindFieldWithConfig_AlreadyBound() {
        // Given
        String modelPid = "model_pid_001";
        FieldBindingRequest request = FieldBindingRequest.builder()
                .fieldPid("field_pid_001")
                .build();

        when(metaModelMapper.findByPid(modelPid)).thenReturn(testModel);
        when(metaFieldMapper.findByPid(request.getFieldPid())).thenReturn(testField);
        when(bindingMapper.selectByModelAndField(testModel.getId(), testField.getId())).thenReturn(testBinding);

        // When & Then
        assertThrows(IllegalStateException.class, () -> 
            bindingService.bindFieldWithConfig(modelPid, request)
        );
    }

    @Test
    @DisplayName("Should throw exception when alias code already exists")
    void testBindFieldWithConfig_DuplicateAliasCode() {
        // Given
        String modelPid = "model_pid_001";
        FieldBindingRequest request = FieldBindingRequest.builder()
                .fieldPid("field_pid_001")
                .aliasCode("existing_alias")
                .build();

        ModelFieldBinding existingBinding = new ModelFieldBinding();
        existingBinding.setAliasCode("existing_alias");

        when(metaModelMapper.findByPid(modelPid)).thenReturn(testModel);
        when(metaFieldMapper.findByPid(request.getFieldPid())).thenReturn(testField);
        when(bindingMapper.selectByModelAndField(testModel.getId(), testField.getId())).thenReturn(null);
        when(bindingMapper.findByModelId(testModel.getId())).thenReturn(Arrays.asList(existingBinding));

        // When & Then
        assertThrows(IllegalStateException.class, () -> 
            bindingService.bindFieldWithConfig(modelPid, request)
        );
    }

    @Test
    @DisplayName("Should batch bind fields with common configuration successfully")
    void testBatchBindFieldsWithConfig_Success() {
        // Given
        String modelPid = "model_pid_001";
        
        Field field1 = new Field();
        field1.setId(201L);
        field1.setPid("field_pid_001");
        field1.setCode("field1");
        field1.setDataType("string");

        Field field2 = new Field();
        field2.setId(202L);
        field2.setPid("field_pid_002");
        field2.setCode("field2");
        field2.setDataType("integer");

        BatchFieldBindingRequest.CommonBindingConfig commonConfig = 
            BatchFieldBindingRequest.CommonBindingConfig.builder()
                .required(true)
                .visible(true)
                .editable(false)
                .build();

        BatchFieldBindingRequest request = BatchFieldBindingRequest.builder()
                .fieldPids(Arrays.asList("field_pid_001", "field_pid_002"))
                .commonConfig(commonConfig)
                .build();

        when(metaModelMapper.findByPid(modelPid)).thenReturn(testModel);
        when(metaFieldMapper.findByPid("field_pid_001")).thenReturn(field1);
        when(metaFieldMapper.findByPid("field_pid_002")).thenReturn(field2);
        when(bindingMapper.selectByModelAndField(eq(testModel.getId()), anyLong())).thenReturn(null);
        when(bindingMapper.getMaxFieldOrder(testModel.getId())).thenReturn(-1);
        when(bindingMapper.insert(any(ModelFieldBinding.class))).thenReturn(1);

        // When
        List<MetaModelFieldBindingDTO> results = bindingService.batchBindFieldsWithConfig(modelPid, request);

        // Then
        assertNotNull(results);
        assertEquals(2, results.size());
        verify(bindingMapper, times(2)).insert(argThat((ModelFieldBinding binding) ->
            binding.getRequired() == true &&
            binding.getEditable() == false &&
            binding.getVisible() == true
        ));
    }

    @Test
    @DisplayName("Should skip already bound fields in batch binding")
    void testBatchBindFieldsWithConfig_SkipAlreadyBound() {
        // Given
        String modelPid = "model_pid_001";
        
        Field field1 = new Field();
        field1.setId(201L);
        field1.setPid("field_pid_001");
        field1.setCode("field1");
        field1.setDataType("string");

        Field field2 = new Field();
        field2.setId(202L);
        field2.setPid("field_pid_002");
        field2.setCode("field2");
        field2.setDataType("integer");

        BatchFieldBindingRequest request = BatchFieldBindingRequest.builder()
                .fieldPids(Arrays.asList("field_pid_001", "field_pid_002"))
                .build();

        when(metaModelMapper.findByPid(modelPid)).thenReturn(testModel);
        when(metaFieldMapper.findByPid("field_pid_001")).thenReturn(field1);
        when(metaFieldMapper.findByPid("field_pid_002")).thenReturn(field2);
        
        // First field already bound
        when(bindingMapper.selectByModelAndField(testModel.getId(), field1.getId())).thenReturn(testBinding);
        // Second field not bound
        when(bindingMapper.selectByModelAndField(testModel.getId(), field2.getId())).thenReturn(null);
        
        when(bindingMapper.getMaxFieldOrder(testModel.getId())).thenReturn(-1);
        when(bindingMapper.insert(any(ModelFieldBinding.class))).thenReturn(1);

        // When
        List<MetaModelFieldBindingDTO> results = bindingService.batchBindFieldsWithConfig(modelPid, request);

        // Then
        assertNotNull(results);
        assertEquals(1, results.size()); // Only one field should be bound
        verify(bindingMapper, times(1)).insert(any(ModelFieldBinding.class));
    }

    @Test
    @DisplayName("Should return empty list when all fields already bound")
    void testBatchBindFieldsWithConfig_AllAlreadyBound() {
        // Given
        String modelPid = "model_pid_001";
        
        Field field1 = new Field();
        field1.setId(201L);
        field1.setPid("field_pid_001");

        BatchFieldBindingRequest request = BatchFieldBindingRequest.builder()
                .fieldPids(Arrays.asList("field_pid_001"))
                .build();

        when(metaModelMapper.findByPid(modelPid)).thenReturn(testModel);
        when(metaFieldMapper.findByPid("field_pid_001")).thenReturn(field1);
        when(bindingMapper.selectByModelAndField(testModel.getId(), field1.getId())).thenReturn(testBinding);

        // When
        List<MetaModelFieldBindingDTO> results = bindingService.batchBindFieldsWithConfig(modelPid, request);

        // Then
        assertNotNull(results);
        assertTrue(results.isEmpty());
        verify(bindingMapper, never()).insert(any(ModelFieldBinding.class));
    }

    @Test
    @DisplayName("Should use default values when common config is null")
    void testBatchBindFieldsWithConfig_NullCommonConfig() {
        // Given
        String modelPid = "model_pid_001";
        
        Field field1 = new Field();
        field1.setId(201L);
        field1.setPid("field_pid_001");
        field1.setCode("field1");
        field1.setDataType("string");

        BatchFieldBindingRequest request = BatchFieldBindingRequest.builder()
                .fieldPids(Arrays.asList("field_pid_001"))
                .commonConfig(null) // No common config
                .build();

        when(metaModelMapper.findByPid(modelPid)).thenReturn(testModel);
        when(metaFieldMapper.findByPid("field_pid_001")).thenReturn(field1);
        when(bindingMapper.selectByModelAndField(testModel.getId(), field1.getId())).thenReturn(null);
        when(bindingMapper.getMaxFieldOrder(testModel.getId())).thenReturn(-1);
        when(bindingMapper.insert(any(ModelFieldBinding.class))).thenReturn(1);

        // When
        List<MetaModelFieldBindingDTO> results = bindingService.batchBindFieldsWithConfig(modelPid, request);

        // Then
        assertNotNull(results);
        assertEquals(1, results.size());
        verify(bindingMapper).insert(argThat((ModelFieldBinding binding) ->
            binding.getRequired() == false &&
            binding.getEditable() == true &&
            binding.getVisible() == true
        ));
    }
}
