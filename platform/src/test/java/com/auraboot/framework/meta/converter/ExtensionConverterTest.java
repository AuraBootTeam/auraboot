package com.auraboot.framework.meta.converter;

import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExtensionConverter Unit Tests
 * 
 * Feature: model-field-management-fixes
 * Property 2: Extension转换往返一致性
 * 
 * 对于任意有效的Extension Map,转换为ExtensionBean再转换回Map,应该得到等价的结构
 * 
 * Validates: Requirements REQ-6
 * 
 * @author AuraBoot Team
 * @since 3.2.9
 */
@Slf4j
@DisplayName("ExtensionConverter Unit Tests")
class ExtensionConverterTest {
    
    private ExtensionConverter converter;
    private Random random;
    
    @BeforeEach
    void setUp() {
        converter = new ExtensionConverter();
        random = new Random();
    }
    
    /**
     * Test: toBean with valid map
     */
    @Test
    @DisplayName("toBean should convert valid map to ExtensionBean")
    void testToBean_ValidMap_Success() {
        // Arrange
        Map<String, Object> map = new HashMap<>();
        map.put("displayWidth", 200);
        map.put("placeholder", "请输入...");
        map.put("maxLength", 100);
        
        // Act
        ExtensionBean bean = converter.toBean(map);
        
        // Assert
        assertNotNull(bean, "Bean should not be null");
        assertNotNull(bean.getExtension(), "Extension should not be null");
        assertEquals(3, bean.getExtension().size(), "Extension size should match");
        assertEquals(200, bean.getExtension().get("displayWidth"));
        assertEquals("请输入...", bean.getExtension().get("placeholder"));
        assertEquals(100, bean.getExtension().get("maxLength"));
        
        log.info("✓ toBean converted map successfully: {}", bean);
    }
    
    /**
     * Test: toBean with null input
     */
    @Test
    @DisplayName("toBean should return null for null input")
    void testToBean_NullInput_ReturnsNull() {
        // Act
        ExtensionBean bean = converter.toBean(null);
        
        // Assert
        assertNull(bean, "Bean should be null for null input");
        
        log.info("✓ toBean correctly handled null input");
    }
    
    /**
     * Test: toBean with empty map
     */
    @Test
    @DisplayName("toBean should return null for empty map")
    void testToBean_EmptyMap_ReturnsNull() {
        // Arrange
        Map<String, Object> emptyMap = new HashMap<>();
        
        // Act
        ExtensionBean bean = converter.toBean(emptyMap);
        
        // Assert
        assertNull(bean, "Bean should be null for empty map");
        
        log.info("✓ toBean correctly handled empty map");
    }
    
    /**
     * Test: toBean with nested map
     */
    @Test
    @DisplayName("toBean should handle nested map structures")
    void testToBean_NestedMap_Success() {
        // Arrange
        Map<String, Object> validation = new HashMap<>();
        validation.put("min", 0);
        validation.put("max", 100);
        validation.put("required", true);
        
        Map<String, Object> map = new HashMap<>();
        map.put("displayWidth", 200);
        map.put("validation", validation);
        
        // Act
        ExtensionBean bean = converter.toBean(map);
        
        // Assert
        assertNotNull(bean);
        assertNotNull(bean.getExtension());
        assertEquals(2, bean.getExtension().size());
        
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedValidation = (Map<String, Object>) bean.getExtension().get("validation");
        assertNotNull(nestedValidation);
        assertEquals(0, nestedValidation.get("min"));
        assertEquals(100, nestedValidation.get("max"));
        assertEquals(true, nestedValidation.get("required"));
        
        log.info("✓ toBean handled nested map successfully");
    }
    
    /**
     * Test: toMap with valid bean
     */
    @Test
    @DisplayName("toMap should convert valid ExtensionBean to Map")
    void testToMap_ValidBean_Success() {
        // Arrange
        Map<String, Object> extension = new HashMap<>();
        extension.put("displayWidth", 200);
        extension.put("placeholder", "请输入...");
        
        ExtensionBean bean = new ExtensionBean();
        bean.setExtension(extension);
        
        // Act
        Map<String, Object> map = converter.toMap(bean);
        
        // Assert
        assertNotNull(map, "Map should not be null");
        assertEquals(2, map.size(), "Map size should match");
        assertEquals(200, map.get("displayWidth"));
        assertEquals("请输入...", map.get("placeholder"));
        
        log.info("✓ toMap converted bean successfully: {}", map);
    }
    
    /**
     * Test: toMap with null input
     */
    @Test
    @DisplayName("toMap should return null for null input")
    void testToMap_NullInput_ReturnsNull() {
        // Act
        Map<String, Object> map = converter.toMap(null);
        
        // Assert
        assertNull(map, "Map should be null for null input");
        
        log.info("✓ toMap correctly handled null input");
    }
    
    /**
     * Test: toMap with null extension
     */
    @Test
    @DisplayName("toMap should return null for bean with null extension")
    void testToMap_NullExtension_ReturnsNull() {
        // Arrange
        ExtensionBean bean = new ExtensionBean();
        bean.setExtension(null);
        
        // Act
        Map<String, Object> map = converter.toMap(bean);
        
        // Assert
        assertNull(map, "Map should be null for null extension");
        
        log.info("✓ toMap correctly handled null extension");
    }
    
    /**
     * Test: toMap with empty extension
     */
    @Test
    @DisplayName("toMap should return null for bean with empty extension")
    void testToMap_EmptyExtension_ReturnsNull() {
        // Arrange
        ExtensionBean bean = new ExtensionBean();
        bean.setExtension(new HashMap<>());
        
        // Act
        Map<String, Object> map = converter.toMap(bean);
        
        // Assert
        assertNull(map, "Map should be null for empty extension");
        
        log.info("✓ toMap correctly handled empty extension");
    }
    
    /**
     * Property 2: Extension转换往返一致性
     * 
     * 对于任意有效的Extension Map,转换为ExtensionBean再转换回Map,
     * 应该得到等价的结构(extension内容相同)
     */
    @RepeatedTest(100)
    @DisplayName("Property 2: Round-trip conversion preserves structure")
    void property_2_roundTripConversionPreservesStructure() {
        // Arrange: 生成随机Extension Map
        Map<String, Object> originalMap = generateRandomExtensionMap();
        
        // Act: 往返转换
        ExtensionBean bean = converter.toBean(originalMap);
        Map<String, Object> resultMap = converter.toMap(bean);
        
        // Assert: 验证结构等价
        assertNotNull(bean, "Bean should not be null");
        assertNotNull(resultMap, "Result map should not be null");
        assertEquals(originalMap.size(), resultMap.size(), "Map size should be preserved");
        
        // 验证所有键值对
        for (Map.Entry<String, Object> entry : originalMap.entrySet()) {
            String key = entry.getKey();
            Object originalValue = entry.getValue();
            Object resultValue = resultMap.get(key);
            
            assertTrue(resultMap.containsKey(key), "Key should be preserved: " + key);
            
            if (originalValue instanceof Map) {
                // 嵌套Map的比较
                assertTrue(resultValue instanceof Map, "Nested map type should be preserved");
                @SuppressWarnings("unchecked")
                Map<String, Object> originalNested = (Map<String, Object>) originalValue;
                @SuppressWarnings("unchecked")
                Map<String, Object> resultNested = (Map<String, Object>) resultValue;
                assertEquals(originalNested.size(), resultNested.size(), "Nested map size should match");
            } else {
                assertEquals(originalValue, resultValue, "Value should be preserved for key: " + key);
            }
        }
        
        log.debug("✓ Round-trip conversion preserved structure: {} keys", originalMap.size());
    }
    
    /**
     * Test: Round-trip with complex nested structure
     */
    @Test
    @DisplayName("Round-trip should preserve complex nested structures")
    void testRoundTrip_ComplexNested_PreservesStructure() {
        // Arrange: 创建复杂嵌套结构
        Map<String, Object> validation = new HashMap<>();
        validation.put("min", 0);
        validation.put("max", 100);
        validation.put("pattern", "^[a-z]+$");
        
        Map<String, Object> display = new HashMap<>();
        display.put("width", 200);
        display.put("height", 50);
        display.put("color", "#FF0000");
        
        Map<String, Object> originalMap = new HashMap<>();
        originalMap.put("validation", validation);
        originalMap.put("display", display);
        originalMap.put("required", true);
        originalMap.put("placeholder", "Enter value");
        
        // Act: 往返转换
        ExtensionBean bean = converter.toBean(originalMap);
        Map<String, Object> resultMap = converter.toMap(bean);
        
        // Assert: 验证复杂结构
        assertNotNull(resultMap);
        assertEquals(4, resultMap.size());
        
        @SuppressWarnings("unchecked")
        Map<String, Object> resultValidation = (Map<String, Object>) resultMap.get("validation");
        assertNotNull(resultValidation);
        assertEquals(3, resultValidation.size());
        assertEquals(0, resultValidation.get("min"));
        assertEquals(100, resultValidation.get("max"));
        assertEquals("^[a-z]+$", resultValidation.get("pattern"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> resultDisplay = (Map<String, Object>) resultMap.get("display");
        assertNotNull(resultDisplay);
        assertEquals(3, resultDisplay.size());
        assertEquals(200, resultDisplay.get("width"));
        assertEquals(50, resultDisplay.get("height"));
        assertEquals("#FF0000", resultDisplay.get("color"));
        
        assertEquals(true, resultMap.get("required"));
        assertEquals("Enter value", resultMap.get("placeholder"));
        
        log.info("✓ Round-trip preserved complex nested structure");
    }
    
    /**
     * Test: Multiple round-trips preserve structure
     */
    @Test
    @DisplayName("Multiple round-trips should preserve structure")
    void testMultipleRoundTrips_PreservesStructure() {
        // Arrange
        Map<String, Object> originalMap = new HashMap<>();
        originalMap.put("key1", "value1");
        originalMap.put("key2", 123);
        originalMap.put("key3", true);
        
        // Act: 多次往返转换
        Map<String, Object> currentMap = originalMap;
        for (int i = 0; i < 5; i++) {
            ExtensionBean bean = converter.toBean(currentMap);
            currentMap = converter.toMap(bean);
        }
        
        // Assert: 验证结构仍然保持
        assertEquals(originalMap.size(), currentMap.size());
        assertEquals("value1", currentMap.get("key1"));
        assertEquals(123, currentMap.get("key2"));
        assertEquals(true, currentMap.get("key3"));
        
        log.info("✓ Multiple round-trips preserved structure");
    }
    
    /**
     * Test: toMap creates defensive copy
     */
    @Test
    @DisplayName("toMap should create defensive copy of extension")
    void testToMap_CreatesDefensiveCopy() {
        // Arrange
        Map<String, Object> extension = new HashMap<>();
        extension.put("key", "value");
        
        ExtensionBean bean = new ExtensionBean();
        bean.setExtension(extension);
        
        // Act
        Map<String, Object> map1 = converter.toMap(bean);
        Map<String, Object> map2 = converter.toMap(bean);
        
        // Modify map1
        map1.put("newKey", "newValue");
        
        // Assert: map2 should not be affected
        assertFalse(map2.containsKey("newKey"), "Defensive copy should prevent modification");
        assertEquals(1, map2.size(), "Original size should be preserved");
        
        // Assert: bean's extension should not be affected
        assertFalse(bean.getExtension().containsKey("newKey"), "Bean should not be affected");
        
        log.info("✓ toMap creates defensive copy");
    }
    
    /**
     * Test: toBean creates defensive copy
     */
    @Test
    @DisplayName("toBean should create defensive copy of input map")
    void testToBean_CreatesDefensiveCopy() {
        // Arrange
        Map<String, Object> originalMap = new HashMap<>();
        originalMap.put("key", "value");
        
        // Act
        ExtensionBean bean = converter.toBean(originalMap);
        
        // Modify original map
        originalMap.put("newKey", "newValue");
        
        // Assert: bean should not be affected
        assertFalse(bean.getExtension().containsKey("newKey"), "Defensive copy should prevent modification");
        assertEquals(1, bean.getExtension().size(), "Original size should be preserved");
        
        log.info("✓ toBean creates defensive copy");
    }
    
    /**
     * Test: toMapViaJson method
     */
    @Test
    @DisplayName("toMapViaJson should convert bean to map via JSON serialization")
    void testToMapViaJson_Success() {
        // Arrange
        Map<String, Object> extension = new HashMap<>();
        extension.put("displayWidth", 200);
        extension.put("placeholder", "请输入...");
        
        ExtensionBean bean = new ExtensionBean();
        bean.setExtension(extension);
        
        // Act
        Map<String, Object> map = converter.toMapViaJson(bean);
        
        // Assert
        assertNotNull(map);
        assertTrue(map.containsKey("extension"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> extensionMap = (Map<String, Object>) map.get("extension");
        assertNotNull(extensionMap);
        assertEquals(200, extensionMap.get("displayWidth"));
        assertEquals("请输入...", extensionMap.get("placeholder"));
        
        log.info("✓ toMapViaJson converted bean successfully");
    }
    
    /**
     * Generate random extension map for property testing
     */
    private Map<String, Object> generateRandomExtensionMap() {
        Map<String, Object> map = new HashMap<>();
        
        // 添加1-5个随机属性
        int propertyCount = random.nextInt(5) + 1;
        for (int i = 0; i < propertyCount; i++) {
            String key = "property_" + i;
            Object value = generateRandomValue();
            map.put(key, value);
        }
        
        return map;
    }
    
    /**
     * Generate random value for testing
     */
    private Object generateRandomValue() {
        int type = random.nextInt(5);
        switch (type) {
            case 0:
                return "string_" + random.nextInt(1000);
            case 1:
                return random.nextInt(1000);
            case 2:
                return random.nextBoolean();
            case 3:
                return random.nextDouble() * 1000;
            case 4:
                // 嵌套Map
                Map<String, Object> nested = new HashMap<>();
                nested.put("nested_key", "nested_value_" + random.nextInt(100));
                nested.put("nested_num", random.nextInt(100));
                return nested;
            default:
                return "default_value";
        }
    }
}
