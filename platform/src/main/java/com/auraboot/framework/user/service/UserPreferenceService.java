package com.auraboot.framework.user.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface UserPreferenceService {

    JsonNode getPreference(Long userId, String key);

    void setPreference(Long userId, String key, JsonNode value);
}
