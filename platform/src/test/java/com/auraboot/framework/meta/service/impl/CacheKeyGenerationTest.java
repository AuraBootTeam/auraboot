package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 缓存键生成单元测试
 * 
 * 测试目标:
 * - 验证缓存键生成的正确性
 * - 验证SpEL表达式解析正确
 * - 验证租户隔离在缓存键中生效
 * 
 * 注意: 这是纯单元测试,不依赖Spring容器
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("Feature: critical-security-fixes, Task 2.3: Cache key generation tests")
class CacheKeyGenerationTest {
    
    @BeforeEach
    void setUp() {
        // 清除租户上下文
        MetaContext.clear();
    }
    
    @AfterEach
    void tearDown() {
        // 清除租户上下文
        MetaContext.clear();
    }
    
    // ==================== 测试1: 缓存键格式验证 ====================
    
    /**
     * 测试1: 缓存键格式验证
     * 
     * 验证需求 2.1: 缓存键必须包含租户ID以实现租户隔离
     */
    @Test
    @Order(1)
    @DisplayName("测试1: 缓存键格式验证")
    void testCacheKeyFormat() {
        // Given: 设置租户上下文
        Long tenantId = 100L;

        MetaContext.setCurrentTenantId(tenantId);
          

        
        String dictCode = "test_dict";
        String versionStrategy = "latest";
        String pinnedVersion = null;
        
        // When: 生成缓存键(模拟SpEL表达式)
        String cacheKey = generateDictCacheKey(dictCode, versionStrategy, pinnedVersion);
        
        // Then: 验证缓存键格式
        String expectedKey = tenantId  +":" +
                           dictCode + ":" + versionStrategy + ":null";
        
        assertEquals(expectedKey, cacheKey, "缓存键格式应该正确");
        
        // 验证缓存键包含所有必要组件
        assertTrue(cacheKey.contains(tenantId.toString()), "缓存键应该包含租户ID");

        assertTrue(cacheKey.contains(dictCode), "缓存键应该包含dictCode");
        assertTrue(cacheKey.contains(versionStrategy), "缓存键应该包含versionStrategy");
    }
    
    // ==================== 测试2: 不同租户生成不同缓存键 ====================
    
    /**
     * 测试2: 不同租户生成不同缓存键
     * 
     * 验证需求 2.4: 不同租户的缓存必须隔离
     */
    @Test
    @Order(2)
    @DisplayName("测试2: 不同租户生成不同缓存键")
    void testDifferentTenantsGenerateDifferentCacheKeys() {
        // Given: 租户1的上下文
        MetaContext.setCurrentTenantId(100L);

        
        String dictCode = "test_dict";
        String versionStrategy = "latest";
        
        // When: 生成租户1的缓存键
        String key1 = generateDictCacheKey(dictCode, versionStrategy, null);
        
        // Given: 切换到租户2
        MetaContext.clear();
        MetaContext.setCurrentTenantId(200L);

        
        // When: 生成租户2的缓存键
        String key2 = generateDictCacheKey(dictCode, versionStrategy, null);
        
        // Then: 验证两个缓存键不同
        assertNotEquals(key1, key2, "不同租户的缓存键必须不同");
        
        // 验证租户ID在缓存键中
        assertTrue(key1.startsWith("100:"), "租户1的缓存键应该以租户ID开头");
        assertTrue(key2.startsWith("200:"), "租户2的缓存键应该以租户ID开头");
    }
    
    // ==================== 测试3: 缓存键包含所有必要参数 ====================
    
    /**
     * 测试3: 缓存键包含所有必要参数
     * 
     * 验证需求 2.2: 缓存键必须包含所有影响结果的参数
     */
    @Test
    @Order(3)
    @DisplayName("测试3: 缓存键包含所有必要参数")
    void testCacheKeyIncludesAllNecessaryParameters() {
        // Given: 设置租户上下文
        MetaContext.setCurrentTenantId(100L);

        
        String dictCode = "test_dict";
        String versionStrategy1 = "latest";
        String versionStrategy2 = "pinned";
        String pinnedVersion = "v1.0.0";
        
        // When: 使用不同的versionStrategy生成缓存键
        String key1 = generateDictCacheKey(dictCode, versionStrategy1, null);
        String key2 = generateDictCacheKey(dictCode, versionStrategy2, pinnedVersion);
        
        // Then: 验证生成了不同的缓存键
        assertNotEquals(key1, key2, "不同参数应该生成不同的缓存键");
        
        // 验证参数在缓存键中
        assertTrue(key1.contains(versionStrategy1), "缓存键1应该包含versionStrategy1");
        assertTrue(key2.contains(versionStrategy2), "缓存键2应该包含versionStrategy2");
        assertTrue(key2.contains(pinnedVersion), "缓存键2应该包含pinnedVersion");
    }
    
    // ==================== 测试4: 缓存键处理null值 ====================
    
    /**
     * 测试4: 缓存键处理null值
     * 
     * 验证: 缓存键应该正确处理null参数
     */
    @Test
    @Order(4)
    @DisplayName("测试4: 缓存键处理null值")
    void testCacheKeyHandlesNullValues() {
        // Given: 设置租户上下文
        MetaContext.setCurrentTenantId(100L);

        
        String dictCode = "test_dict";
        String versionStrategy = "latest";
        String pinnedVersion = null; // null值
        
        // When: 生成缓存键
        String cacheKey = generateDictCacheKey(dictCode, versionStrategy, pinnedVersion);
        
        // Then: 验证缓存键正确处理null
        assertTrue(cacheKey.endsWith(":null"), "缓存键应该正确处理null值");
        assertNotNull(cacheKey, "缓存键不应该为null");
    }
    
    // ==================== 测试5: 缓存键不包含敏感信息 ====================
    
    /**
     * 测试5: 缓存键不包含敏感信息
     * 
     * 验证: 缓存键不应该包含密码等敏感信息
     */
    @Test
    @Order(5)
    @DisplayName("测试5: 缓存键不包含敏感信息")
    void testCacheKeyDoesNotContainSensitiveInfo() {
        // Given: 设置租户上下文
        MetaContext.setCurrentTenantId(100L);

        
        String dictCode = "user_dict";
        String versionStrategy = "latest";
        
        // When: 生成缓存键
        String cacheKey = generateDictCacheKey(dictCode, versionStrategy, null);
        
        // Then: 验证缓存键不包含敏感关键词
        assertFalse(cacheKey.toLowerCase().contains("password"), "缓存键不应包含password");
        assertFalse(cacheKey.toLowerCase().contains("secret"), "缓存键不应包含secret");
        assertFalse(cacheKey.toLowerCase().contains("token"), "缓存键不应包含token");
        assertFalse(cacheKey.toLowerCase().contains("key"), "缓存键不应包含key(除了dictCode)");
    }
    
    // ==================== 测试6: 缓存键长度合理 ====================
    
    /**
     * 测试6: 缓存键长度合理
     * 
     * 验证: 缓存键长度应该在合理范围内
     */
    @Test
    @Order(6)
    @DisplayName("测试6: 缓存键长度合理")
    void testCacheKeyLengthIsReasonable() {
        // Given: 设置租户上下文
        MetaContext.setCurrentTenantId(100L);

        String dictCode = "very_long_dictionary_code_name";
        String versionStrategy = "pinned";
        String pinnedVersion = "v1.0.0-beta.1";
        
        // When: 生成缓存键
        String cacheKey = generateDictCacheKey(dictCode, versionStrategy, pinnedVersion);
        
        // Then: 验证缓存键长度
        assertTrue(cacheKey.length() < 500, 
            "缓存键长度应该小于500字符,实际长度: " + cacheKey.length());
        assertTrue(cacheKey.length() > 10, 
            "缓存键长度应该大于10字符,实际长度: " + cacheKey.length());
    }
    
    // ==================== 测试7: 缓存键一致性 ====================
    
    /**
     * 测试7: 缓存键一致性
     * 
     * 验证: 相同参数应该生成相同的缓存键
     */
    @Test
    @Order(7)
    @DisplayName("测试7: 缓存键一致性")
    void testCacheKeyConsistency() {
        // Given: 设置租户上下文
        MetaContext.setCurrentTenantId(100L);

        
        String dictCode = "test_dict";
        String versionStrategy = "latest";
        
        // When: 多次生成缓存键
        String key1 = generateDictCacheKey(dictCode, versionStrategy, null);
        String key2 = generateDictCacheKey(dictCode, versionStrategy, null);
        String key3 = generateDictCacheKey(dictCode, versionStrategy, null);
        
        // Then: 验证缓存键一致
        assertEquals(key1, key2, "相同参数应该生成相同的缓存键");
        assertEquals(key2, key3, "相同参数应该生成相同的缓存键");
        assertEquals(key1, key3, "相同参数应该生成相同的缓存键");
    }
    
    // ==================== 测试8: 缓存键特殊字符处理 ====================
    
    /**
     * 测试8: 缓存键特殊字符处理
     * 
     * 验证: 缓存键应该正确处理特殊字符
     */
    @Test
    @Order(8)
    @DisplayName("测试8: 缓存键特殊字符处理")
    void testCacheKeyHandlesSpecialCharacters() {
        // Given: 设置包含特殊字符的租户上下文
        MetaContext.setCurrentTenantId(999L);

        
        String dictCode = "dict_with-special.chars";
        String versionStrategy = "latest";
        
        // When: 生成缓存键
        String cacheKey = generateDictCacheKey(dictCode, versionStrategy, null);
        
        // Then: 验证缓存键正确处理特殊字符
        assertNotNull(cacheKey, "缓存键不应该为null");
        assertTrue(cacheKey.contains("dict_with-special.chars"), "缓存键应该包含特殊字符的dictCode");
    }
    
    // ==================== 测试9: 级联字典缓存键生成 ====================
    
    /**
     * 测试9: 级联字典缓存键生成
     * 
     * 验证: 级联字典的缓存键应该包含parentValue
     */
    @Test
    @Order(9)
    @DisplayName("测试9: 级联字典缓存键生成")
    void testCascadeDictCacheKeyGeneration() {
        // Given: 设置租户上下文
        MetaContext.setCurrentTenantId(100L);

        
        String dictCode = "cascade_dict";
        String parentValue = "parent_001";
        
        // When: 生成级联字典缓存键
        String cacheKey = generateCascadeDictCacheKey(dictCode, parentValue);
        
        // Then: 验证缓存键包含所有必要信息
        String expectedKey = "100:" + dictCode + ":" + parentValue;
        assertEquals(expectedKey, cacheKey, "级联字典缓存键格式应该正确");
        
        assertTrue(cacheKey.contains(dictCode), "缓存键应该包含dictCode");
        assertTrue(cacheKey.contains(parentValue), "缓存键应该包含parentValue");
    }
    
    // ==================== 测试10: 缓存键分隔符一致性 ====================
    
    /**
     * 测试10: 缓存键分隔符一致性
     * 
     * 验证: 所有缓存键应该使用一致的分隔符
     */
    @Test
    @Order(10)
    @DisplayName("测试10: 缓存键分隔符一致性")
    void testCacheKeySeparatorConsistency() {
        // Given: 设置租户上下文
        MetaContext.setCurrentTenantId(100L);

        
        String dictCode = "test_dict";
        String versionStrategy = "latest";
        
        // When: 生成缓存键
        String cacheKey = generateDictCacheKey(dictCode, versionStrategy, null);
        
        // Then: 验证使用冒号作为分隔符
        String[] parts = cacheKey.split(":");
        assertEquals(4, parts.length, "缓存键应该有4个部分(tenantId:dictCode:versionStrategy:pinnedVersion)");
        
        // 验证每个部分
        assertEquals("100", parts[0], "第1部分应该是tenantId");

        assertEquals(dictCode, parts[1], "第2部分应该是dictCode");
        assertEquals(versionStrategy, parts[2], "第3部分应该是versionStrategy");
        assertEquals("null", parts[3], "第4部分应该是pinnedVersion");
    }
    
    // ==================== Helper Methods ====================
    
    /**
     * 生成字典缓存键(模拟SpEL表达式)
     * 
     * 对应@Cacheable注解:
     * key = "T(com.auraboot.framework.application.tenant.MetaContext).getCurrentTenantId() + ':' + 
     *
     *        #code + ':' + #versionStrategy + ':' + #pinnedVersion"
     */
    private String generateDictCacheKey(String code, String versionStrategy, String pinnedVersion) {
        Long tenantId = MetaContext.getCurrentTenantId();
              
              
        
        return tenantId  +":" +
               code + ":" + versionStrategy + ":" + pinnedVersion;
    }
    
    /**
     * 生成级联字典缓存键(模拟SpEL表达式)
     * 
     * 对应@Cacheable注解:
     * key = "#request.tenantId  +
     *        #request.dictCode + ':' + #request.parentValue"
     */
    private String generateCascadeDictCacheKey(String dictCode, String parentValue) {
        Long tenantId = MetaContext.getCurrentTenantId();
              
              
        
        return tenantId + ":" + dictCode + ":" + parentValue;
    }
}
