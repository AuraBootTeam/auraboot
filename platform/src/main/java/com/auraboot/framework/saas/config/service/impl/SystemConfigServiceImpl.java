package com.auraboot.framework.saas.config.service.impl;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.saas.config.entity.SystemConfigEntity;
import com.auraboot.framework.saas.config.mapper.SystemConfigMapper;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.constant.SaasKernelErrorCode;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigServiceImpl implements SystemConfigService {

    private final SystemConfigMapper systemConfigMapper;

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private volatile long cacheExpiry = 0;
    private static final long CACHE_TTL_MS = 60_000;

    @Override
    public Optional<String> get(String key) {
        String cached = getCached(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        SystemConfigEntity entity = systemConfigMapper.findByKey(key);
        if (entity == null) {
            return Optional.empty();
        }
        cache.put(key, entity.getConfigValue());
        return Optional.of(entity.getConfigValue());
    }

    @Override
    public String getOrDefault(String key, String defaultValue) {
        return get(key).orElse(defaultValue);
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        return get(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    @Override
    public long getLong(String key, long defaultValue) {
        return get(key).map(Long::parseLong).orElse(defaultValue);
    }

    @Override
    @Transactional
    public void set(String key, String value) {
        SystemConfigEntity entity = systemConfigMapper.findByKey(key);
        if (entity == null) {
            throw new BusinessException(
                SaasKernelErrorCode.CONFIG_KEY_NOT_FOUND.getMessage() + ": " + key
            );
        }
        if (Boolean.TRUE.equals(entity.getIsReadonly())) {
            throw new BusinessException(
                SaasKernelErrorCode.CONFIG_READONLY.getMessage() + ": " + key
            );
        }
        entity.setConfigValue(value);
        entity.setUpdatedAt(Instant.now());
        systemConfigMapper.updateById(entity);
        cache.put(key, value);
        log.info("System config updated: {} = {}", key, value);
    }

    @Override
    @Transactional
    public void initialize(String key, String value, String scope, String valueType,
                           String description, boolean readonly) {
        SystemConfigEntity existing = systemConfigMapper.findByKey(key);
        if (existing != null) {
            if (!value.equals(existing.getConfigValue())) {
                existing.setConfigValue(value);
                existing.setUpdatedAt(Instant.now());
                systemConfigMapper.updateById(existing);
            }
            cache.put(key, value);
            return;
        }
        SystemConfigEntity entity = new SystemConfigEntity();
        entity.setPid(UlidGenerator.generate());
        entity.setConfigScope(scope);
        entity.setConfigKey(key);
        entity.setConfigValue(value);
        entity.setValueType(valueType);
        entity.setDescription(description);
        entity.setIsReadonly(readonly);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        systemConfigMapper.insert(entity);
        cache.put(key, value);
        log.info("System config initialized: {} = {} (readonly={})", key, value, readonly);
    }

    @Override
    public boolean isInitialized() {
        return getBoolean(SystemConfigKeys.SYSTEM_INITIALIZED, false);
    }

    @Override
    public void evictCache() {
        cache.clear();
        cacheExpiry = 0;
    }

    private String getCached(String key) {
        if (System.currentTimeMillis() > cacheExpiry) {
            cache.clear();
            cacheExpiry = System.currentTimeMillis() + CACHE_TTL_MS;
            return null;
        }
        return cache.get(key);
    }
}
