package com.auraboot.framework.meta.validator;

import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldValidationResult;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.service.DictService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MetaFieldValidator Unit Tests
 * 
 * Feature: model-field-management-fixes
 * Property 3: Field validation completeness
 * 
 * For any field create request, if validation fails, the field should not be created;
 * if validation succeeds, the field should be created successfully.
 * 
 * Validates: Requirements REQ-7
 * 
 * @author AuraBoot Team
 * @since 3.2.9
 */
@Slf4j
@ExtendWith(MockitoExtension.class)
@DisplayName("MetaFieldValidator Unit Tests")
class MetaFieldValidatorTest {
    
    @Mock
    private MetaFieldMapper metaFieldMapper;
    
    @Mock
    private DictService dictService;
    
    private MetaFieldValidator validator;
    private Random random;
    
    @BeforeEach
    void setUp() {
        validator = new MetaFieldValidator(metaFieldMapper, dictService);
        random = new Random();
        
        // Default mock behavior: code is unique (lenient to avoid UnnecessaryStubbingException)
        lenient().when(metaFieldMapper.findAllVersionsByCode(anyString()))
            .thenReturn(Collections.emptyList());
    }
    
    /**
     * Test: Valid field code format
     */
    @Test
    @DisplayName("validateCreateRequest should accept valid field code format")
    void testValidateCreateRequest_ValidCodeFormat_Success() {
        // Arrange
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode("user_name");
        request.setDataType("string");
        
        // Act
        MetaFieldValidationResult result = validator.validateCreateRequest(request);
        
        // Assert
        assertTrue(result.isValid(), "Valid code should pass validation");
        assertFalse(result.hasErrors(), "No errors should be present");
        
        log.info("✓ Valid code format accepted: {}", request.getCode());
    }
    
    /**
     * Test: Invalid code format - starts with number
     */
    @Test
    @DisplayName("validateCreateRequest should reject code starting with number")
    void testValidateCreateRequest_CodeStartsWithNumber_Fails() {
        // Arrange
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode("123invalid");
        request.setDataType("string");
        
        // Act
        MetaFieldValidationResult result = validator.validateCreateRequest(request);
        
        // Assert
        assertFalse(result.isValid(), "Code starting with number should fail");
        assertTrue(result.hasErrors(), "Should have errors");
        assertTrue(result.getErrors().stream()
            .anyMatch(e -> "code".equals(e.getField())), "Should have code error");
        
        log.info("✓ Invalid code format rejected: {}", request.getCode());
    }
    
    /**
     * Test: Invalid code format - contains uppercase
     */
    @Test
    @DisplayName("validateCreateRequest should reject code with uppercase letters")
    void testValidateCreateRequest_CodeWithUppercase_Fails() {
        // Arrange
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode("UserName");
        request.setDataType("string");
        
        // Act
        MetaFieldValidationResult result = validator.validateCreateRequest(request);
        
        // Assert
        assertFalse(result.isValid(), "Code with uppercase should fail");
        assertTrue(result.hasErrors(), "Should have errors");
        
        log.info("✓ Code with uppercase rejected: {}", request.getCode());
    }
    
    /**
     * Test: Invalid code format - contains special characters
     */
    @Test
    @DisplayName("validateCreateRequest should reject code with special characters")
    void testValidateCreateRequest_CodeWithSpecialChars_Fails() {
        // Arrange
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode("user-name");
        request.setDataType("string");
        
        // Act
        MetaFieldValidationResult result = validator.validateCreateRequest(request);
        
        // Assert
        assertFalse(result.isValid(), "Code with special characters should fail");
        assertTrue(result.hasErrors(), "Should have errors");
        
        log.info("✓ Code with special characters rejected: {}", request.getCode());
    }
    
    /**
     * Test: Invalid code format - too long
     */
    @Test
    @DisplayName("validateCreateRequest should reject code that is too long")
    void testValidateCreateRequest_CodeTooLong_Fails() {
        // Arrange
        String longCode = "a".repeat(65);
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(longCode);
        request.setDataType("string");
        
        // Act
        MetaFieldValidationResult result = validator.validateCreateRequest(request);
        
        // Assert
        assertFalse(result.isValid(), "Code too long should fail");
        assertTrue(result.hasErrors(), "Should have errors");
        
        log.info("✓ Code too long rejected: length={}", longCode.length());
    }
    
    /**
     * Test: Valid data types
     */
    @Test
    @DisplayName("validateCreateRequest should accept all valid data types")
    void testValidateCreateRequest_ValidDataTypes_Success() {
        String[] validTypes = {
            "string", "text", "integer", "long", "decimal", "boolean",
            "date", "datetime", "time", "json", "array", "object",
            "reference", "enum", "file", "image"
        };
        
        for (String dataType : validTypes) {
            // Arrange
            MetaFieldCreateRequest request = new MetaFieldCreateRequest();
            request.setCode("test_field");
            request.setDataType(dataType);
            
            // Act
            MetaFieldValidationResult result = validator.validateCreateRequest(request);
            
            // Assert
            assertTrue(result.isValid(), "Valid data type should pass: " + dataType);
            assertFalse(result.getErrors().stream()
                .anyMatch(e -> "dataType".equals(e.getField())), 
                "Should not have dataType error for: " + dataType);
        }
        
        log.info("✓ All {} valid data types accepted", validTypes.length);
    }
    
    /**
     * Test: Invalid data type
     */
    @Test
    @DisplayName("validateCreateRequest should reject invalid data type")
    void testValidateCreateRequest_InvalidDataType_Fails() {
        // Arrange
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode("test_field");
        request.setDataType("invalid_type");
        
        // Act
        MetaFieldValidationResult result = validator.validateCreateRequest(request);
        
        // Assert
        assertFalse(result.isValid(), "Invalid data type should fail");
        assertTrue(result.getErrors().stream()
            .anyMatch(e -> "dataType".equals(e.getField())), "Should have dataType error");
        
        log.info("✓ Invalid data type rejected: {}", request.getDataType());
    }
    
    /**
     * Test: Null data type
     */
    @Test
    @DisplayName("validateCreateRequest should reject null data type")
    void testValidateCreateRequest_NullDataType_Fails() {
        // Arrange
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode("test_field");
        request.setDataType(null);
        
        // Act
        MetaFieldValidationResult result = validator.validateCreateRequest(request);
        
        // Assert
        assertFalse(result.isValid(), "Null data type should fail");
        assertTrue(result.hasErrors(), "Should have errors");
        
        log.info("✓ Null data type rejected");
    }
    
    /**
     * Test: Duplicate code
     */
    @Test
    @DisplayName("validateCreateRequest should reject duplicate code")
    void testValidateCreateRequest_DuplicateCode_Fails() {
        // Arrange - Setup MetaContext
        try {
            com.auraboot.framework.application.tenant.MetaContext.setSystemTenantContext(1L);

            MetaFieldCreateRequest request = new MetaFieldCreateRequest();
            request.setCode("existing_field");
            request.setDataType("string");
            
            // Mock: code already exists
            Field existingField = new Field();
            existingField.setPid("existing-pid");
            existingField.setCode("existing_field");
            when(metaFieldMapper.findAllVersionsByCode("existing_field"))
                .thenReturn(java.util.List.of(existingField));
            
            // Act
            MetaFieldValidationResult result = validator.validateCreateRequest(request);
            
            // Assert
            assertFalse(result.isValid(), "Duplicate code should fail");
            assertTrue(result.getErrors().stream()
                .anyMatch(e -> "code".equals(e.getField()) && "duplicate".equals(e.getErrorCode())), 
                "Should have duplicate code error");
            
            log.info("✓ Duplicate code rejected");
        } finally {
            com.auraboot.framework.application.tenant.MetaContext.clear();
        }
    }
    
    /**
     * Test: Multiple validation errors
     */
    @Test
    @DisplayName("validateCreateRequest should collect multiple validation errors")
    void testValidateCreateRequest_MultipleErrors_CollectsAll() {
        // Arrange
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode("123Invalid-Code");  // Invalid: starts with number, has uppercase, has hyphen
        request.setDataType("invalid_type");  // Invalid data type
        
        // Act
        MetaFieldValidationResult result = validator.validateCreateRequest(request);
        
        // Assert
        assertFalse(result.isValid(), "Should fail validation");
        assertTrue(result.getErrorCount() >= 2, "Should have multiple errors");
        
        log.info("✓ Multiple errors collected: {}", result.getErrorCount());
    }
    
    /**
     * Property 3: 字段验证完整性
     * 
     * 对于任意字段创建请求,验证结果应该准确反映字段定义的有效性
     */
    @RepeatedTest(100)
    @DisplayName("Property 3: Validation completeness")
    void property_3_validationCompleteness() {
        // Arrange: 生成随机字段请求
        MetaFieldCreateRequest request = generateRandomFieldRequest();
        
        // Act: 验证字段
        MetaFieldValidationResult result = validator.validateCreateRequest(request);
        
        // Assert: 验证结果的一致性
        if (result.isValid()) {
            // 如果验证通过,应该没有错误
            assertFalse(result.hasErrors(), 
                "Valid request should have no errors");
            
            // 验证代码格式正确
            assertTrue(request.getCode().matches("^[a-z][a-z0-9_]*$"),
                "Valid code should match pattern");
            
            // 验证数据类型有效
            assertNotNull(request.getDataType(), "Valid request should have data type");
        } else {
            // 如果验证失败,应该有错误信息
            assertTrue(result.hasErrors(), 
                "Invalid request should have errors");
            
            // 验证错误信息不为空
            for (var error : result.getErrors()) {
                assertNotNull(error.getField(), "Error field should not be null");
                assertNotNull(error.getMessage(), "Error message should not be null");
            }
        }
        
        log.debug("✓ Validation completeness verified: valid={}, errors={}", 
            result.isValid(), result.getErrorCount());
    }
    
    /**
     * Test: Validation result structure
     */
    @Test
    @DisplayName("validateCreateRequest should return properly structured result")
    void testValidateCreateRequest_ResultStructure_Correct() {
        // Arrange
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode("valid_code");
        request.setDataType("string");
        
        // Act
        MetaFieldValidationResult result = validator.validateCreateRequest(request);
        
        // Assert
        assertNotNull(result, "Result should not be null");
        assertNotNull(result.getErrors(), "Errors list should not be null");
        assertTrue(result.isValid(), "Should be valid");
        assertFalse(result.hasErrors(), "Should have no errors");
        
        log.info("✓ Validation result structure correct");
    }
    
    /**
     * Test: Error messages are descriptive
     */
    @Test
    @DisplayName("validateCreateRequest should provide descriptive error messages")
    void testValidateCreateRequest_ErrorMessages_Descriptive() {
        // Arrange
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode("123invalid");
        request.setDataType("invalid_type");
        
        // Act
        MetaFieldValidationResult result = validator.validateCreateRequest(request);
        
        // Assert
        assertFalse(result.isValid());
        
        for (var error : result.getErrors()) {
            assertNotNull(error.getMessage(), "Error message should not be null");
            assertFalse(error.getMessage().trim().isEmpty(), "Error message should not be empty");
            assertTrue(error.getMessage().length() > 5, "Error message should be descriptive");
        }
        
        log.info("✓ Error messages are descriptive");
    }
    
    /**
     * Test: Validation is consistent
     */
    @Test
    @DisplayName("validateCreateRequest should produce consistent results for same input")
    void testValidateCreateRequest_Consistency_SameResults() {
        // Arrange
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode("test_field");
        request.setDataType("string");
        
        // Act: 多次验证
        MetaFieldValidationResult result1 = validator.validateCreateRequest(request);
        MetaFieldValidationResult result2 = validator.validateCreateRequest(request);
        MetaFieldValidationResult result3 = validator.validateCreateRequest(request);
        
        // Assert: 结果应该一致
        assertEquals(result1.isValid(), result2.isValid(), "Results should be consistent");
        assertEquals(result2.isValid(), result3.isValid(), "Results should be consistent");
        assertEquals(result1.getErrorCount(), result2.getErrorCount(), 
            "Error count should be consistent");
        assertEquals(result2.getErrorCount(), result3.getErrorCount(), 
            "Error count should be consistent");
        
        log.info("✓ Validation is consistent");
    }
    
    /**
     * Generate random field request for property testing
     */
    private MetaFieldCreateRequest generateRandomFieldRequest() {
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        
        // 50% chance of valid code, 50% chance of invalid code
        if (random.nextBoolean()) {
            request.setCode(generateValidCode());
        } else {
            request.setCode(generateInvalidCode());
        }
        
        // 80% chance of valid data type, 20% chance of invalid
        if (random.nextDouble() < 0.8) {
            request.setDataType(generateValidDataType());
        } else {
            request.setDataType(generateInvalidDataType());
        }
        
        return request;
    }
    
    /**
     * Generate valid field code
     */
    private String generateValidCode() {
        String[] prefixes = {"user", "order", "product", "customer", "item"};
        String[] suffixes = {"name", "code", "id", "type", "status"};
        
        String prefix = prefixes[random.nextInt(prefixes.length)];
        String suffix = suffixes[random.nextInt(suffixes.length)];
        
        return prefix + "_" + suffix;
    }
    
    /**
     * Generate invalid field code
     */
    private String generateInvalidCode() {
        int type = random.nextInt(4);
        switch (type) {
            case 0:
                return "123invalid";  // Starts with number
            case 1:
                return "InvalidCode";  // Contains uppercase
            case 2:
                return "invalid-code";  // Contains hyphen
            case 3:
                return "ab";  // Too short (but actually valid according to pattern)
            default:
                return "invalid code";  // Contains space
        }
    }
    
    /**
     * Generate valid data type
     */
    private String generateValidDataType() {
        String[] validTypes = {
            "string", "text", "integer", "long", "decimal", "boolean",
            "date", "datetime", "time", "json", "array", "object"
        };
        return validTypes[random.nextInt(validTypes.length)];
    }
    
    /**
     * Generate invalid data type
     */
    private String generateInvalidDataType() {
        String[] invalidTypes = {
            "invalid_type", "unknown", "bad_type", ""
        };
        int index = random.nextInt(invalidTypes.length);
        return invalidTypes[index].isEmpty() ? null : invalidTypes[index];
    }
}
