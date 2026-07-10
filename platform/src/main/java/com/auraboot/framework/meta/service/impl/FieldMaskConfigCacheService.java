package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.FieldMaskConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

/** Cache boundary for enabled field-mask configurations. */
@Service
public class FieldMaskConfigCacheService {

    @Cacheable(
            value = "fieldMaskConfig",
            key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':' + #modelCode")
    public List<FieldMaskConfig> getEnabledConfigs(
            String modelCode,
            Supplier<List<FieldMaskConfig>> loader) {
        return loader.get();
    }

    @CacheEvict(
            value = "fieldMaskConfig",
            key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':' + #modelCode")
    public void evict(String modelCode) {
        // Eviction is performed by the Spring cache interceptor.
    }
}
