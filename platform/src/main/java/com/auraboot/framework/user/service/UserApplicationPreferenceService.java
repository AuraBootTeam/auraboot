package com.auraboot.framework.user.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface UserApplicationPreferenceService {
    String LAST_TENANT_KEY = "routing.last_tenant";

    JsonNode get(Long userId, Long applicationId, String key);

    void set(Long userId, Long applicationId, String key, JsonNode value, int schemaVersion);

    String getLastTenantPid(Long userId, Long applicationId);

    void setLastTenant(Long userId, Long applicationId, String tenantPid);
}
