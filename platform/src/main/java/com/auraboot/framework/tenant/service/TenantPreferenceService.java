package com.auraboot.framework.tenant.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface TenantPreferenceService {

    JsonNode getPreference(Long tenantId, String key);

    void setPreference(Long tenantId, String key, JsonNode value);
}
