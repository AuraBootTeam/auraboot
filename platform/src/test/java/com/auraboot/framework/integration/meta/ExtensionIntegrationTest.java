package com.auraboot.framework.integration.meta;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.converter.ExtensionConverter;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.service.MetaFieldService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extension integration tests
 * 
 * Tests Extension persistence, retrieval, and null handling
 * 
 * @author AuraBoot Team
 * @since 2.1.2
 */
@Slf4j
@Transactional
@Rollback(true)
@DisplayName("Extension Integration Tests")
public class ExtensionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MetaFieldService fieldService;

    @Autowired
    private MetaFieldMapper fieldMapper;

    @Autowired
    private ExtensionConverter extensionConverter;

    @Test
    @DisplayName("Test Extension save to database")
    void testExtensionSaveToDatabase() {
        // Given: Field with Extension data
        String fieldCode = "test_field_ext_" + System.currentTimeMillis();
        
        Map<String, Object> extension = new HashMap<>();
        extension.put("displayWidth", 300);
        extension.put("placeholder", "Enter value");
        extension.put("maxLength", 100);
        extension.put("validation", Map.of("required", true, "pattern", "^[A-Z]+$"));
        
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(fieldCode);
        request.setDataType("string");
          
        request.setExtension(extension);

        // When: Create field
        MetaFieldDTO createdField = fieldService.create(request);
        assertNotNull(createdField, "Field should be created");
        
        log.info("Created field with extension: code={}, pid={}", createdField.getCode(), createdField.getPid());

        // Then: Verify Extension was saved to database
        Optional<MetaFieldDTO> retrievedFieldOpt = fieldService.findCurrentByCode(fieldCode);
        assertTrue(retrievedFieldOpt.isPresent(), "Field should be retrievable");
        
        MetaFieldDTO retrievedField = retrievedFieldOpt.get();
        assertNotNull(retrievedField.getExtension(), "Extension should not be null");
        
        // Verify Extension content
        Map<String, Object> retrievedExtension = retrievedField.getExtension();
        assertEquals(300, retrievedExtension.get("displayWidth"), "displayWidth should match");
        assertEquals("Enter value", retrievedExtension.get("placeholder"), "placeholder should match");
        assertEquals(100, retrievedExtension.get("maxLength"), "maxLength should match");
        
        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) retrievedExtension.get("validation");
        assertNotNull(validation, "validation should not be null");
        assertEquals(true, validation.get("required"), "validation.required should match");
        assertEquals("^[A-Z]+$", validation.get("pattern"), "validation.pattern should match");
        
        log.info("Extension save test passed: Extension correctly persisted to database");
    }

    @Test
    @DisplayName("Test Extension read from database")
    void testExtensionReadFromDatabase() {
        // Given: Field with Extension saved in database
        String fieldCode = "test_field_read_" + System.currentTimeMillis();
        
        Map<String, Object> originalExtension = new HashMap<>();
        originalExtension.put("displayWidth", 250);
        originalExtension.put("placeholder", "Test placeholder");
        originalExtension.put("customProperty", "customValue");
        
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(fieldCode);
        request.setDataType("integer");
          
        request.setExtension(originalExtension);
        
        MetaFieldDTO createdField = fieldService.create(request);
        assertNotNull(createdField);

        // When: Read field from database
        Optional<MetaFieldDTO> retrievedFieldOpt = fieldService.findCurrentByCode(fieldCode);
        assertTrue(retrievedFieldOpt.isPresent(), "Field should be found");
        
        MetaFieldDTO retrievedField = retrievedFieldOpt.get();

        // Then: Verify Extension was correctly read
        assertNotNull(retrievedField.getExtension(), "Extension should not be null");
        
        Map<String, Object> retrievedExtension = retrievedField.getExtension();
        assertEquals(3, retrievedExtension.size(), "Extension should have 3 properties");
        assertEquals(250, retrievedExtension.get("displayWidth"));
        assertEquals("Test placeholder", retrievedExtension.get("placeholder"));
        assertEquals("customValue", retrievedExtension.get("customProperty"));
        
        log.info("Extension read test passed: Extension correctly read from database");
    }

    @Test
    @DisplayName("Test null Extension handling")
    void testNullExtensionHandling() {
        // Given: Field without Extension
        String fieldCode = "test_field_null_ext_" + System.currentTimeMillis();
        
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(fieldCode);
        request.setDataType("boolean");
          
        request.setExtension(null);  // Explicitly set to null

        // When: Create field
        MetaFieldDTO createdField = fieldService.create(request);
        assertNotNull(createdField, "Field should be created");

        // Then: Verify null Extension is handled correctly
        Optional<MetaFieldDTO> retrievedFieldOpt = fieldService.findCurrentByCode(fieldCode);
        assertTrue(retrievedFieldOpt.isPresent(), "Field should be found");
        
        MetaFieldDTO retrievedField = retrievedFieldOpt.get();
        
        // Extension should be null or empty
        Map<String, Object> extension = retrievedField.getExtension();
        assertTrue(extension == null || extension.isEmpty(), 
            "Extension should be null or empty when not provided");
        
        log.info("Null Extension test passed: null Extension handled correctly");
    }

    @Test
    @DisplayName("Test empty Extension handling")
    void testEmptyExtensionHandling() {
        // Given: Field with empty Extension
        String fieldCode = "test_field_empty_ext_" + System.currentTimeMillis();
        
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(fieldCode);
        request.setDataType("date");
          
        request.setExtension(new HashMap<>());  // Empty map

        // When: Create field
        MetaFieldDTO createdField = fieldService.create(request);
        assertNotNull(createdField, "Field should be created");

        // Then: Verify empty Extension is handled correctly
        Optional<MetaFieldDTO> retrievedFieldOpt = fieldService.findCurrentByCode(fieldCode);
        assertTrue(retrievedFieldOpt.isPresent(), "Field should be found");
        
        MetaFieldDTO retrievedField = retrievedFieldOpt.get();
        
        // Extension should be null or empty
        Map<String, Object> extension = retrievedField.getExtension();
        assertTrue(extension == null || extension.isEmpty(), 
            "Extension should be null or empty when empty map provided");
        
        log.info("Empty Extension test passed: empty Extension handled correctly");
    }

    @Test
    @DisplayName("Test Extension with nested objects")
    void testExtensionWithNestedObjects() {
        // Given: Field with nested Extension structure
        String fieldCode = "test_field_nested_" + System.currentTimeMillis();
        
        Map<String, Object> extension = new HashMap<>();
        extension.put("displayWidth", 400);
        
        Map<String, Object> validation = new HashMap<>();
        validation.put("required", true);
        validation.put("minLength", 5);
        validation.put("maxLength", 50);
        
        Map<String, Object> pattern = new HashMap<>();
        pattern.put("regex", "^[A-Za-z0-9]+$");
        pattern.put("message", "Only alphanumeric characters allowed");
        validation.put("pattern", pattern);
        
        extension.put("validation", validation);
        
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(fieldCode);
        request.setDataType("string");
          
        request.setExtension(extension);

        // When: Create field
        MetaFieldDTO createdField = fieldService.create(request);
        assertNotNull(createdField);

        // Then: Verify nested Extension structure is preserved
        Optional<MetaFieldDTO> retrievedFieldOpt = fieldService.findCurrentByCode(fieldCode);
        assertTrue(retrievedFieldOpt.isPresent());
        
        MetaFieldDTO retrievedField = retrievedFieldOpt.get();
        assertNotNull(retrievedField.getExtension());
        
        Map<String, Object> retrievedExtension = retrievedField.getExtension();
        assertEquals(400, retrievedExtension.get("displayWidth"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> retrievedValidation = (Map<String, Object>) retrievedExtension.get("validation");
        assertNotNull(retrievedValidation);
        assertEquals(true, retrievedValidation.get("required"));
        assertEquals(5, retrievedValidation.get("minLength"));
        assertEquals(50, retrievedValidation.get("maxLength"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> retrievedPattern = (Map<String, Object>) retrievedValidation.get("pattern");
        assertNotNull(retrievedPattern);
        assertEquals("^[A-Za-z0-9]+$", retrievedPattern.get("regex"));
        assertEquals("Only alphanumeric characters allowed", retrievedPattern.get("message"));
        
        log.info("Nested Extension test passed: nested structure correctly preserved");
    }

    @Test
    @DisplayName("Test Extension converter round-trip")
    void testExtensionConverterRoundTrip() {
        // Given: Extension Map
        Map<String, Object> originalMap = new HashMap<>();
        originalMap.put("key1", "value1");
        originalMap.put("key2", 123);
        originalMap.put("key3", true);
        originalMap.put("nested", Map.of("subKey", "subValue"));

        // When: Convert Map -> Bean -> Map
        ExtensionBean bean = extensionConverter.toBean(originalMap);
        assertNotNull(bean, "Bean should not be null");
        
        Map<String, Object> convertedMap = extensionConverter.toMap(bean);
        assertNotNull(convertedMap, "Converted map should not be null");

        // Then: Verify round-trip consistency
        assertEquals(originalMap.size(), convertedMap.size(), "Size should match");
        assertEquals("value1", convertedMap.get("key1"));
        assertEquals(123, convertedMap.get("key2"));
        assertEquals(true, convertedMap.get("key3"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) convertedMap.get("nested");
        assertNotNull(nested);
        assertEquals("subValue", nested.get("subKey"));
        
        log.info("Extension converter round-trip test passed");
    }
}
