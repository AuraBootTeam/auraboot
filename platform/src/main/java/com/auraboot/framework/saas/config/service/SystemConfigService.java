package com.auraboot.framework.saas.config.service;

import java.util.Optional;

public interface SystemConfigService {
    Optional<String> get(String key);
    String getOrDefault(String key, String defaultValue);
    boolean getBoolean(String key, boolean defaultValue);
    long getLong(String key, long defaultValue);
    void set(String key, String value);
    void initialize(String key, String value, String scope, String valueType,
                    String description, boolean readonly);
    boolean isInitialized();

    /**
     * Evict the in-memory cache. Used by integration tests after truncating
     * {@code ab_system_config} to ensure subsequent reads hit the DB. Production
     * code should not normally need this — the cache TTL (60s) handles staleness.
     */
    void evictCache();
}
