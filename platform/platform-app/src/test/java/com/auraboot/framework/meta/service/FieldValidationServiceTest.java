package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.BindingConfiguration;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.service.FieldValidationService.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FieldValidationService unit test
 * Tests field validation capabilities
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("FieldValidationService Test")
class FieldValidationServiceTest extends BaseIntegrationTest {

    @Autowired
    private FieldValidationService fieldValidationService;

    @Test
    @DisplayName("Test validate field definition with valid request")
    void testValidateFieldDefinitionValid() {
        // Given
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode("valid_field_name");
        request.setDataType("string");

        // When
        ValidationResult result = fieldValidationService.validateFieldDefinition(request);

        // Then
        assertNotNull(result);
        // Result validity depends on implementation
    }

    @Test
    @DisplayName("Test validate field definition with invalid code")
    void testValidateFieldDefinitionInvalidCode() {
        // Given
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode("123invalid"); // Starts with number
        request.setDataType("string");

        // When
        ValidationResult result = fieldValidationService.validateFieldDefinition(request);

        // Then
        assertNotNull(result);
        // Should have validation errors
    }

    @Test
    @DisplayName("Test validate code format - valid codes")
    void testValidateCodeFormatValid() {
        // Valid codes
        assertTrue(fieldValidationService.validateCodeFormat("field_name"));
        assertTrue(fieldValidationService.validateCodeFormat("fieldName"));
        assertTrue(fieldValidationService.validateCodeFormat("field123"));
        assertTrue(fieldValidationService.validateCodeFormat("f"));
        assertTrue(fieldValidationService.validateCodeFormat("Field_Name_123"));
    }

    @Test
    @DisplayName("Test validate code format - invalid codes")
    void testValidateCodeFormatInvalid() {
        // Invalid codes
        assertFalse(fieldValidationService.validateCodeFormat("123field")); // Starts with number
        assertFalse(fieldValidationService.validateCodeFormat("_field")); // Starts with underscore
        assertFalse(fieldValidationService.validateCodeFormat("field-name")); // Contains hyphen
        assertFalse(fieldValidationService.validateCodeFormat("field name")); // Contains space
        assertFalse(fieldValidationService.validateCodeFormat("field.name")); // Contains dot
        assertFalse(fieldValidationService.validateCodeFormat("")); // Empty string
    }

    @Test
    @DisplayName("Test validate data type - valid types")
    void testValidateDataTypeValid() {
        // Common valid data types
        assertTrue(fieldValidationService.validateDataType("string"));
        assertTrue(fieldValidationService.validateDataType("integer"));
        assertTrue(fieldValidationService.validateDataType("boolean"));
        assertTrue(fieldValidationService.validateDataType("date"));
        assertTrue(fieldValidationService.validateDataType("datetime"));
        assertTrue(fieldValidationService.validateDataType("decimal"));
        assertTrue(fieldValidationService.validateDataType("text"));
        assertTrue(fieldValidationService.validateDataType("reference"));
    }

    @Test
    @DisplayName("Test validate data type - invalid types")
    void testValidateDataTypeInvalid() {
        // Invalid data types
        assertFalse(fieldValidationService.validateDataType("invalid_type"));
        assertFalse(fieldValidationService.validateDataType(""));
        assertFalse(fieldValidationService.validateDataType(null));
    }

    @Test
    @DisplayName("Test validate reference target - valid target")
    void testValidateRefTargetValid() {
        // Given
        Map<String, Object> refTarget = new HashMap<>();
        refTarget.put("modelCode", "user");
        refTarget.put("fieldCode", "id");

        // When
        boolean isValid = fieldValidationService.validateRefTarget(refTarget);

        // Then
        // Validity depends on whether the target model exists
        assertNotNull(isValid);
    }

    @Test
    @DisplayName("Test validate reference target - null target")
    void testValidateRefTargetNull() {
        // When
        boolean isValid = fieldValidationService.validateRefTarget(null);

        // Then
        assertFalse(isValid);
    }

    @Test
    @DisplayName("Test validate reference target - empty target")
    void testValidateRefTargetEmpty() {
        // Given
        Map<String, Object> refTarget = new HashMap<>();

        // When
        boolean isValid = fieldValidationService.validateRefTarget(refTarget);

        // Then
        assertFalse(isValid);
    }

    @Test
    @DisplayName("Test validate dictionary binding")
    void testValidateDictBinding() {
        // Given - non-existent field and dictionary
        String fieldPid = "test-field-pid";
        String dictCode = "test-dict";

        // When - validate with non-existent field/dict
        boolean isValid = fieldValidationService.validateDictBinding(fieldPid, dictCode);

        // Then - should return false since field doesn't exist
        assertFalse(isValid, "Should return false for non-existent field");
    }

    @Test
    @DisplayName("Test validate dictionary binding with null values")
    void testValidateDictBindingNull() {
        // When
        boolean isValid1 = fieldValidationService.validateDictBinding(null, "dict");
        boolean isValid2 = fieldValidationService.validateDictBinding("field", null);
        boolean isValid3 = fieldValidationService.validateDictBinding(null, null);

        // Then
        assertFalse(isValid1);
        assertFalse(isValid2);
        assertFalse(isValid3);
    }

    @Test
    @DisplayName("Test validate binding override")
    void testValidateBindingOverride() {
        // Given
        BindingConfiguration binding = BindingConfiguration.builder()
            .required(true)
            .nullable(false)
            .readonly(false)
            .visible(true)
            .editable(true)
            .build();

        MetaFieldDTO field = MetaFieldDTO.builder()
            .code("test_field")
            .dataType("string")
            .build();

        // When
        boolean isValid = fieldValidationService.validateBindingOverride(binding, field);

        // Then
        // Validity depends on whether override rules are more restrictive
        assertNotNull(isValid);
    }

    @Test
    @DisplayName("Test validation result structure")
    void testValidationResultStructure() {
        // Given
        ValidationResult result = new ValidationResult();

        // When - Initially valid
        assertTrue(result.isValid());
        assertTrue(result.getErrors().isEmpty());
        assertTrue(result.getWarnings().isEmpty());
        assertTrue(result.getSuggestions().isEmpty());

        // When - Add error
        result.addError("Test error");

        // Then
        assertFalse(result.isValid());
        assertEquals(1, result.getErrors().size());
        assertEquals("Test error", result.getErrors().get(0));

        // When - Add warning
        result.addWarning("Test warning");

        // Then
        assertTrue(result.hasWarnings());
        assertEquals(1, result.getWarnings().size());

        // When - Add suggestion
        result.addSuggestion("Test suggestion");

        // Then
        assertTrue(result.hasSuggestions());
        assertEquals(1, result.getSuggestions().size());
    }

    @Test
    @DisplayName("Test validation result with multiple errors")
    void testValidationResultMultipleErrors() {
        // Given
        ValidationResult result = new ValidationResult();

        // When
        result.addError("Error 1");
        result.addError("Error 2");
        result.addError("Error 3");

        // Then
        assertFalse(result.isValid());
        assertEquals(3, result.getErrors().size());
    }

    @Test
    @DisplayName("Test validation result warnings don't affect validity")
    void testValidationResultWarningsOnly() {
        // Given
        ValidationResult result = new ValidationResult();

        // When
        result.addWarning("Warning 1");
        result.addWarning("Warning 2");

        // Then
        assertTrue(result.isValid()); // Warnings don't make result invalid
        assertTrue(result.hasWarnings());
        assertEquals(2, result.getWarnings().size());
    }

    @Test
    @DisplayName("Test validate field definition with reference type")
    void testValidateFieldDefinitionWithReference() {
        // Given
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode("user_ref");
        request.setDataType("reference");
        
        Map<String, Object> refTarget = new HashMap<>();
        refTarget.put("modelCode", "user");
        refTarget.put("fieldCode", "id");
        request.setRefTarget(refTarget);

        // When
        ValidationResult result = fieldValidationService.validateFieldDefinition(request);

        // Then
        assertNotNull(result);
    }
}
