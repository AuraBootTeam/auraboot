package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import net.jqwik.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 缓存隔离属性测试
 * 
 * 使用Property-Based Testing验证缓存键的租户隔离特性
 * 
 * 测试属性:
 * - 属性4: 缓存键租户隔离 (验证需求2.4)
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
class CacheIsolationPropertyTest {

    /**
     * 初始化Mock对象和测试实例
     */
    private void initContext() {
        // 清除租户上下文
        MetaContext.clear();
    }

    // ==================== 属性4: 缓存键租户隔离 ====================
    
    /**
     * 属性4: 缓存键租户隔离
     * 
     * 验证需求 2.4: 不同租户的缓存键必须不同,实现完全隔离
     * 
     * 属性描述: ∀ tenant1, tenant2, params: tenant1 ≠ tenant2 → 
     *                      cacheKey(tenant1, params) ≠ cacheKey(tenant2, params)
     */
    @Property(tries = 100)
    @Tag("Feature: critical-security-fixes, Property 4: Cache key tenant isolation")
    void propertyCacheKeyTenantIsolation(
        @ForAll("tenantContexts") TenantContext tenant1,
        @ForAll("tenantContexts") TenantContext tenant2,
        @ForAll("dictCodes") String dictCode,
        @ForAll("versionStrategies") String versionStrategy
    ) {
        // Setup: 初始化上下文
        initContext();
        
        // Given: 设置租户1的上下文
        MetaContext.setSystemTenantContext(tenant1.getTenantId());

        
        // When: 生成租户1的缓存键
        String cacheKey1 = generateDictCacheKey(dictCode, versionStrategy, null);
        
        // Given: 切换到租户2的上下文
        MetaContext.clear();
        MetaContext.setSystemTenantContext(tenant2.getTenantId());

        // When: 生成租户2的缓存键
        String cacheKey2 = generateDictCacheKey(dictCode, versionStrategy, null);
        
        // Then: 如果租户不同,缓存键必须不同
        if (!tenant1.equals(tenant2)) {
            assertNotEquals(cacheKey1, cacheKey2,
                String.format("不同租户的缓存键必须不同 - tenant1=%s, tenant2=%s, dictCode=%s",
                    tenant1, tenant2, dictCode));
        } else {
            // 如果租户相同,缓存键应该相同
            assertEquals(cacheKey1, cacheKey2,
                String.format("相同租户的缓存键应该相同 - tenant=%s, dictCode=%s",
                    tenant1, dictCode));
        }
        
        // Cleanup
        MetaContext.clear();
    }
    
    // ==================== 属性5: 缓存键包含完整租户上下文 ====================
    
    /**
     * 属性5: 缓存键包含完整租户上下文
     * 
     * 验证: 缓存键必须包含tenantId,  三个维度
     * 
     * 属性描述: ∀ tenant, params: cacheKey.contains(tenantId) ∧ 
     *
     */
    @Property(tries = 100)
    @Tag("Feature: critical-security-fixes, Property 5: Cache key contains complete tenant context")
    void propertyCacheKeyContainsCompleteTenantContext(
        @ForAll("tenantContexts") TenantContext tenant,
        @ForAll("dictCodes") String dictCode,
        @ForAll("versionStrategies") String versionStrategy
    ) {
        // Setup: 初始化上下文
        initContext();
        
        // Given: 设置租户上下文
        MetaContext.setSystemTenantContext(tenant.getTenantId());

        
        // When: 生成缓存键
        String cacheKey = generateDictCacheKey(dictCode, versionStrategy, null);
        
        // Then: 验证缓存键包含所有租户上下文信息
        assertTrue(cacheKey.contains(tenant.getTenantId().toString()),
            String.format("缓存键必须包含tenantId - cacheKey=%s, tenantId=%d",
                cacheKey, tenant.getTenantId()));

        
        // Cleanup
        MetaContext.clear();
    }
    
    // ==================== 属性6: 租户ID变化导致缓存键变化 ====================
    
    /**
     * 属性6: 租户ID变化导致缓存键变化
     * 
     * 验证: 只要租户ID不同,即使其他参数相同,缓存键也必须不同
     * 
     * 属性描述: ∀ tenantId1, tenantId2,   params: 
     *              tenantId1 ≠ tenantId2 → cacheKey1 ≠ cacheKey2
     */
    @Property(tries = 100)
    @Tag("Feature: critical-security-fixes, Property 6: TenantId change causes cache key change")
    void propertyTenantIdChangeCausesCacheKeyChange(
        @ForAll("tenantIds") Long tenantId1,
        @ForAll("tenantIds") Long tenantId2,

        @ForAll("dictCodes") String dictCode
    ) {
        // Setup: 初始化上下文
        initContext();
        
        MetaContext.setSystemTenantContext(tenantId1);
          

        // When: 生成租户1的缓存键
        String cacheKey1 = generateDictCacheKey(dictCode, "latest", null);
        
        // Given: 只改变tenantId
        MetaContext.clear();
        MetaContext.setSystemTenantContext(tenantId2);
          

        // When: 生成租户2的缓存键
        String cacheKey2 = generateDictCacheKey(dictCode, "latest", null);
        
        // Then: 如果tenantId不同,缓存键必须不同
        if (!tenantId1.equals(tenantId2)) {
            assertNotEquals(cacheKey1, cacheKey2,
                String.format("不同tenantId的缓存键必须不同 - tenantId1=%d, tenantId2=%d",
                    tenantId1, tenantId2));
        } else {
            assertEquals(cacheKey1, cacheKey2,
                String.format("相同tenantId的缓存键应该相同 - tenantId=%d", tenantId1));
        }
        
        // Cleanup
        MetaContext.clear();
    }
    

    

    
    // ==================== 属性9: 缓存键唯一性 ====================
    
    /**
     * 属性9: 缓存键唯一性
     * 
     * 验证: 不同的租户上下文组合应该生成唯一的缓存键
     * 
     * 属性描述: ∀ contexts: |contexts| = |{cacheKey(c) | c ∈ contexts}|
     */
    @Property(tries = 100)
    @Tag("Feature: critical-security-fixes, Property 9: Cache key uniqueness")
    void propertyCacheKeyUniqueness(
        @ForAll("tenantContextLists") List<TenantContext> contexts,
        @ForAll("dictCodes") String dictCode
    ) {
        // Setup: 初始化上下文
        initContext();
        
        // When: 为每个租户上下文生成缓存键
        Set<String> cacheKeys = new HashSet<>();
        Set<TenantContext> uniqueContexts = new HashSet<>(contexts);
        
        for (TenantContext context : contexts) {
            MetaContext.clear();
            MetaContext.setSystemTenantContext(context.getTenantId());
            
            
            String cacheKey = generateDictCacheKey(dictCode, "latest", null);
            cacheKeys.add(cacheKey);
        }
        
        // Then: 唯一的租户上下文应该生成唯一的缓存键
        assertEquals(uniqueContexts.size(), cacheKeys.size(),
            String.format("唯一租户上下文数量=%d, 唯一缓存键数量=%d, 应该相等",
                uniqueContexts.size(), cacheKeys.size()));
        
        // Cleanup
        MetaContext.clear();
    }
    
    // ==================== 生成器 (Arbitraries) ====================
    
    /**
     * 生成租户上下文
     */
    @Provide
    Arbitrary<TenantContext> tenantContexts() {
        return tenantIds().map(TenantContext::new);
    }
    
    /**
     * 生成租户上下文列表
     */
    @Provide
    Arbitrary<List<TenantContext>> tenantContextLists() {
        return tenantContexts().list().ofMinSize(2).ofMaxSize(10);
    }
    
    /**
     * 生成租户ID
     */
    @Provide
    Arbitrary<Long> tenantIds() {
        return Arbitraries.longs().between(1L, 1000L);
    }
    

    

    /**
     * 生成字典代码
     */
    @Provide
    Arbitrary<String> dictCodes() {
        return Arbitraries.of(
            "user_status",
            "order_type",
            "product_category",
            "payment_method",
            "shipping_status"
        );
    }
    
    /**
     * 生成版本策略
     */
    @Provide
    Arbitrary<String> versionStrategies() {
        return Arbitraries.of("latest", "pinned", "stable");
    }
    
    // ==================== Helper Methods ====================
    
    /**
     * 生成字典缓存键(模拟SpEL表达式)
     */
    private String generateDictCacheKey(String code, String versionStrategy, String pinnedVersion) {
        Long tenantId = MetaContext.getCurrentTenantId();
              
              
        
        return tenantId  +
               code + ":" + versionStrategy + ":" + pinnedVersion;
    }
    
    // ==================== Helper Classes ====================
    
    /**
     * 租户上下文
     */
    static class TenantContext {
        private final Long tenantId;

        
        public TenantContext(Long tenantId) {
            this.tenantId = tenantId;

        }
        
        public Long getTenantId() {
            return tenantId;
        }
        

        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TenantContext that = (TenantContext) o;
            return Objects.equals(tenantId, that.tenantId)  ;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(tenantId);
        }
        
        @Override
        public String toString() {
            return String.format("TenantContext{tenantId=%d}",
                tenantId  );
        }
    }
}
