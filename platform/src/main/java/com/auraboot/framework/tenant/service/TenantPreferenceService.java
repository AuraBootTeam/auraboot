package com.auraboot.framework.tenant.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public interface TenantPreferenceService {

    JsonNode getPreference(Long tenantId, String key);

    void setPreference(Long tenantId, String key, JsonNode value);

    void deletePreference(Long tenantId, String key);

    Map<String, JsonNode> getPreferencesByPrefix(Long tenantId, String prefix);
}
