package com.auraboot.framework.application.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * 缓存配置类
 * 
 * 为系统提供缓存管理器，支持以下缓存区域：
 * - dictData: 字典数据缓存
 * - namedQueries: 命名查询缓存
 * - queryStatistics: 查询统计缓存
 * - cascadeDict: 级联字典缓存
 * - cascadeTree: 级联字典树缓存
 * - effectivePermissions: 有效权限缓存
 * - permissionCheck: 权限检查缓存
 * - userPermissions: 用户权限缓存
 * - rolePermissions: 角色权限缓存
 * - userRoles: 用户角色缓存
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * 配置缓存管理器
     * 
     * 使用 ConcurrentMapCacheManager 作为默认实现，适用于单机部署。
     * 在生产环境中，可以考虑替换为 Redis 等分布式缓存解决方案。
     * 
     * @return CacheManager 缓存管理器实例
     */
    @Bean
    @Primary
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .recordStats());

        // Pre-create cache regions
        cacheManager.setCacheNames(java.util.Arrays.asList(
            // Dict caches
            "dictData",
            "cascadeDict",
            "cascadeTree",
            "dictMetadata",
            "dictCache",
            "dictItems",
            "dictExists",

            // Query caches
            "namedQuery",
            "namedQueries",
            "queryStatistics",
            "secureQuery",
            "aggregateQuery",

            // Permission caches (Legacy)
            "effectivePermissions",
            "permissionCheck",
            "userPermissions",
            "rolePermissions",
            "userRoles",

            // Permission V4 caches
            "user-permissions",
            "subject-evaluation",
            "subject-declarations",

            // Permission projection caches
            "schemaPermissionProjection",
            "dataFilterResult",
            "fieldPermissionProjection",

            // Model metadata caches
            "modelDefinitions",
            "fieldDefinitions",
            "relationDefinitions",
            "modelExists",

            // Field metadata caches
            "metaField",
            "metaFieldByKey",

            // Model field binding caches
            "modelFieldBindings",
            "fieldBindings",

            // ViewModel caches
            "viewModelFields",
            "viewModelSummary",

            // DSL projection caches
            "modelMetadata",
            "modelCache",
            "fieldMetadata",
            "modelFields",
            "fieldCache",

            // Data permission caches
            "dataPermissionRowFilter",
            "dataPermissionMaskRules",
            "dataScopeCondition",

            // Command execution caches (N+1 optimization)
            "commandDefinitions",
            "bindingRules",
            "stateGraphDefinitions"
        ));

        cacheManager.setAllowNullValues(false);

        return cacheManager;
    }

    /**
     * 配置Jackson ObjectMapper
     * 
     * 用于JSON序列化和反序列化，特别是在DSL投影过程中
     * 处理extension字段的JSONB数据
     * 
     * @return ObjectMapper JSON处理器实例
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        
        // 注册Java 8时间模块
        mapper.registerModule(new JavaTimeModule());
        
        // 禁用将日期写为时间戳
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        
        // 忽略未知属性
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        return mapper;
    }
}