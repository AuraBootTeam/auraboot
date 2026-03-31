package com.auraboot.framework.user.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public interface UserPreferenceService {

    JsonNode getPreference(Long userId, String key);

    void setPreference(Long userId, String key, JsonNode value);

    Map<String, JsonNode> getPreferencesByPrefix(Long userId, String prefix);
}
