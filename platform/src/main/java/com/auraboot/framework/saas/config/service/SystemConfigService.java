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
}
