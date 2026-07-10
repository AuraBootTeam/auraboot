package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.ModelDefinition;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Cache boundary for assembled model definitions.
 *
 * <p>Keeping the cache interceptor on a separate bean makes cache hits work for
 * both external service calls and self-invocation inside {@link MetaModelServiceImpl}.
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
}
