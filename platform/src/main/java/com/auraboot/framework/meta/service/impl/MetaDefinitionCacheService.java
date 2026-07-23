package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.ModelDefinition;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Cache boundary for assembled model definitions.
 *
 * <p>Keeping the cache interceptor on a separate bean makes cache hits <em>and
 * evictions</em> work for both external service calls and self-invocation inside
 * {@link MetaModelServiceImpl}. The eviction MUST live here too: a {@code @CacheEvict}
 * placed on {@code MetaModelServiceImpl} is bypassed when reached via self-invocation
 * (e.g. {@code getModelDefinitionFromDb} → {@code evictModelCache} → {@code refreshModelCache}),
 * which would leave {@code getModelDefinitionFromDb} returning a stale assembled definition
 * — e.g. a field bound moments earlier appears "not found".
 */
@Service
public class MetaDefinitionCacheService {

    @Cacheable(
            value = "modelDefinitions",
            key = "#modelCode + '_' + T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix()",
            unless = "#result == null")
    public Optional<ModelDefinition> getModelDefinition(
            String modelCode,
            Supplier<Optional<ModelDefinition>> loader) {
        return loader.get();
    }

    @CacheEvict(
            value = "modelDefinitions",
            key = "#modelCode + '_' + T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix()")
    public void evict(String modelCode) {
        // No body: the @CacheEvict interceptor removes the assembled definition. Kept on
        // this separate bean so the eviction fires even under self-invocation.
    }
}
