package com.auraboot.framework.integration.meta;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaFieldValidationResult;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.validator.MetaFieldValidator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Field validation integration tests
 * 
 * Tests various validation rules and scenarios
 * 
 * @author AuraBoot Team
 * @since 2.1.2
 */
@Slf4j
@Transactional
@Rollback(true)
@DisplayName("Field Validation Integration Tests")
public class FieldValidationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MetaFieldService fieldService;

    @Autowired
    private MetaFieldValidator fieldValidator;

    @Test
    @DisplayName("Test valid field creation")
    void testValidFieldCreation() {
        // Given: Valid field request
        String fieldCode = "valid_field_" + System.currentTimeMillis();
        
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(fieldCode);
        request.setDataType("string");


        // When: Validate request
        MetaFieldValidationResult validationResult = fieldValidator.validateCreateRequest(request);

        // Then: Validation should pass
        assertTrue(validationResult.isValid(), "Validation should pass for valid field");
        assertTrue(validationResult.getErrors().isEmpty(), "Should have no errors");
        
        // And: Field should be created successfully
        MetaFieldDTO createdField = fieldService.create(request);
        assertNotNull(createdField, "Field should be created");
        assertEquals(fieldCode, createdField.getCode());
        
        log.info("Valid field creation test passed: field={}", fieldCode);
    }

    @Test
    @DisplayName("Test invalid field code format")
    void testInvalidFieldCodeFormat() {
        // Given: Field with invalid code (starts with number)
        MetaFieldCreateRequest request1 = new MetaFieldCreateRequest();
        request1.setCode("123invalid");
        request1.setDataType("string");


        // When: Validate request
        MetaFieldValidationResult result1 = fieldValidator.validateCreateRequest(request1);

        // Then: Validation should fail
        assertFalse(result1.isValid(), "Validation should fail for code starting with number");
        assertTrue(result1.hasError("code"), "Should have code error");
        
        // Given: Field with invalid code (contains uppercase)
        MetaFieldCreateRequest request2 = new MetaFieldCreateRequest();
        request2.setCode("InvalidCode");
        request2.setDataType("string");


        // When: Validate request
        MetaFieldValidationResult result2 = fieldValidator.validateCreateRequest(request2);

        // Then: Validation should fail
        assertFalse(result2.isValid(), "Validation should fail for code with uppercase");
        assertTrue(result2.hasError("code"), "Should have code error");
        
        // Given: Field with invalid code (contains special characters)
        MetaFieldCreateRequest request3 = new MetaFieldCreateRequest();
        request3.setCode("invalid-code");
        request3.setDataType("string");


        // When: Validate request
        MetaFieldValidationResult result3 = fieldValidator.validateCreateRequest(request3);

        // Then: Validation should fail
        assertFalse(result3.isValid(), "Validation should fail for code with special characters");
        assertTrue(result3.hasError("code"), "Should have code error");
        
        log.info("Invalid field code format test passed");
    }

    @Test
    @DisplayName("Test duplicate field code")
    void testDuplicateFieldCode() {
        // Given: Existing field
        String fieldCode = "duplicate_test_" + System.currentTimeMillis();
        
        MetaFieldCreateRequest request1 = new MetaFieldCreateRequest();
        request1.setCode(fieldCode);
        request1.setDataType("string");

        
        MetaFieldDTO existingField = fieldService.create(request1);
        assertNotNull(existingField);

        // When: Try to create field with same code
        MetaFieldCreateRequest request2 = new MetaFieldCreateRequest();
        request2.setCode(fieldCode);
        request2.setDataType("integer");


        // Then: Validation should fail
        MetaFieldValidationResult validationResult = fieldValidator.validateCreateRequest(request2);
        assertFalse(validationResult.isValid(), "Validation should fail for duplicate code");
        assertTrue(validationResult.hasError("code"), "Should have code error");
        
        // And: Creation should throw exception
        assertThrows(ValidationException.class, () -> {
            fieldService.create(request2);
        }, "Should throw ValidationException for duplicate code");
        
        log.info("Duplicate field code test passed");
    }

    @Test
    @DisplayName("Test invalid data type")
    void testInvalidDataType() {
        // Given: Field with invalid data type
        String fieldCode = "invalid_type_" + System.currentTimeMillis();
        
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(fieldCode);
        request.setDataType("invalid_type");
          

        // When: Validate request
        MetaFieldValidationResult validationResult = fieldValidator.validateCreateRequest(request);

        // Then: Validation should fail
        assertFalse(validationResult.isValid(), "Validation should fail for invalid data type");
        assertTrue(validationResult.hasError("dataType"), "Should have dataType error");
        
        log.info("Invalid data type test passed");
    }

    @Test
    @DisplayName("Test missing required fields")
    void testMissingRequiredFields() {
        // Given: Field without code
        MetaFieldCreateRequest request1 = new MetaFieldCreateRequest();
        request1.setCode(null);
        request1.setDataType("string");


        // When: Validate request
        MetaFieldValidationResult result1 = fieldValidator.validateCreateRequest(request1);

        // Then: Validation should fail
        assertFalse(result1.isValid(), "Validation should fail for missing code");
        assertTrue(result1.hasError("code"), "Should have code error");
        
        // Given: Field without data type
        String fieldCode = "missing_type_" + System.currentTimeMillis();
        MetaFieldCreateRequest request2 = new MetaFieldCreateRequest();
        request2.setCode(fieldCode);
        request2.setDataType(null);


        // When: Validate request
        MetaFieldValidationResult result2 = fieldValidator.validateCreateRequest(request2);

        // Then: Validation should fail
        assertFalse(result2.isValid(), "Validation should fail for missing data type");
        assertTrue(result2.hasError("dataType"), "Should have dataType error");
        
        log.info("Missing required fields test passed");
    }

    @Test
    @DisplayName("Test field code length validation")
    void testFieldCodeLengthValidation() {
        // Given: Field with code exceeding max length (64 characters)
        String longCode = "a" + "x".repeat(64);  // 65 characters
        
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(longCode);
        request.setDataType("string");
          

        // When: Validate request
        MetaFieldValidationResult validationResult = fieldValidator.validateCreateRequest(request);

        // Then: Validation should fail
        assertFalse(validationResult.isValid(), "Validation should fail for code exceeding max length");
        assertTrue(validationResult.hasError("code"), "Should have code error");
        
        log.info("Field code length validation test passed");
    }

    @Test
    @DisplayName("Test ENUM type field validation")
    void testEnumTypeFieldValidation() {
        // Given: ENUM type field without dictionary binding
        String fieldCode = "enum_field_" + System.currentTimeMillis();
        
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(fieldCode);
        request.setDataType("enum");
        request.setDataSourceId(null);  // No dictionary binding
          

        // When: Validate request
        MetaFieldValidationResult validationResult = fieldValidator.validateCreateRequest(request);

        // Then: Validation should pass but with warning
        assertTrue(validationResult.isValid(), "Validation should pass");
        assertTrue(validationResult.hasWarnings(), "Should have warnings");
        assertTrue(validationResult.hasWarning("dataSourceId"), "Should have dataSourceId warning");
        
        log.info("ENUM type field validation test passed");
    }

    @Test
    @DisplayName("Test field validation with Extension")
    void testFieldValidationWithExtension() {
        // Given: Field with valid Extension
        String fieldCode = "field_with_ext_" + System.currentTimeMillis();
        
        Map<String, Object> extension = new HashMap<>();
        extension.put("displayWidth", 300);
        extension.put("placeholder", "Enter value");
        
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(fieldCode);
        request.setDataType("string");
        request.setExtension(extension);
          

        // When: Validate and create field
        MetaFieldValidationResult validationResult = fieldValidator.validateCreateRequest(request);

        // Then: Validation should pass
        assertTrue(validationResult.isValid(), "Validation should pass");
        
        // And: Field should be created with Extension
        MetaFieldDTO createdField = fieldService.create(request);
        assertNotNull(createdField);
        assertNotNull(createdField.getExtension());
        
        log.info("Field validation with Extension test passed");
    }

    @Test
    @DisplayName("Test multiple validation errors")
    void testMultipleValidationErrors() {
        // Given: Field with multiple validation errors
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode("123InvalidCode");  // Invalid: starts with number
        request.setDataType("invalid_type");  // Invalid: not a valid type
          

        // When: Validate request
        MetaFieldValidationResult validationResult = fieldValidator.validateCreateRequest(request);

        // Then: Validation should fail with multiple errors
        assertFalse(validationResult.isValid(), "Validation should fail");
        assertTrue(validationResult.getErrors().size() >= 2, "Should have at least 2 errors");
        assertTrue(validationResult.hasError("code"), "Should have code error");
        assertTrue(validationResult.hasError("dataType"), "Should have dataType error");
        
        log.info("Multiple validation errors test passed: found {} errors", 
            validationResult.getErrors().size());
    }

    @Test
    @DisplayName("Test validation result structure")
    void testValidationResultStructure() {
        // Given: Invalid field request
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode("InvalidCode");
        request.setDataType("string");
          

        // When: Validate request
        MetaFieldValidationResult result = fieldValidator.validateCreateRequest(request);

        // Then: Verify result structure
        assertNotNull(result, "Result should not be null");
        assertNotNull(result.getErrors(), "Errors list should not be null");
        assertNotNull(result.getWarnings(), "Warnings list should not be null");
        assertFalse(result.isValid(), "Should be invalid");
        
        // Verify error structure
        assertTrue(result.hasError("code"), "Should have code error");
        
        result.getErrors().forEach(error -> {
            assertNotNull(error.getField(), "Error field should not be null");
            assertNotNull(error.getErrorCode(), "Error code should not be null");
            assertNotNull(error.getMessage(), "Error message should not be null");
        });
        
        log.info("Validation result structure test passed");
    }
}
