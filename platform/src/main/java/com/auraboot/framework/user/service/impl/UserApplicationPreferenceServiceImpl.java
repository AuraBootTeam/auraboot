package com.auraboot.framework.user.service.impl;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.user.dao.entity.UserApplicationPreference;
import com.auraboot.framework.user.mapper.UserApplicationPreferenceMapper;
import com.auraboot.framework.user.service.UserApplicationPreferenceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UserApplicationPreferenceServiceImpl implements UserApplicationPreferenceService {
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_.-]{0,127}$");
    private static final int MAX_VALUE_BYTES = 16 * 1024;
    private static final Set<String> REGISTERED_KEYS = Set.of(LAST_TENANT_KEY);

    private final UserApplicationPreferenceMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public JsonNode get(Long userId, Long applicationId, String key) {
        validateCoordinates(userId, applicationId, key);
        UserApplicationPreference preference = mapper.find(userId, applicationId, key);
        return preference == null ? null : preference.getPreferenceValue();
    }

    @Override
    @Transactional
    public void set(Long userId, Long applicationId, String key, JsonNode value, int schemaVersion) {
        validateCoordinates(userId, applicationId, key);
        if (!REGISTERED_KEYS.contains(key)) {
            throw new IllegalArgumentException("Unregistered application preference key: " + key);
        }
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Application preference value is required");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("Application preference value exceeds 16 KiB");
            }
            mapper.upsert(UlidGenerator.generate(), userId, applicationId, key, json, schemaVersion);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Application preference value is not valid JSON", e);
        }
    }

    @Override
    public String getLastTenantPid(Long userId, Long applicationId) {
        JsonNode value = get(userId, applicationId, LAST_TENANT_KEY);
        JsonNode tenantPid = value == null ? null : value.get("tenantPid");
        return tenantPid != null && tenantPid.isTextual() && !tenantPid.asText().isBlank()
                ? tenantPid.asText()
                : null;
    }

    @Override
    public void setLastTenant(Long userId, Long applicationId, String tenantPid) {
        if (tenantPid == null || tenantPid.isBlank()) {
            throw new IllegalArgumentException("tenantPid is required");
        }
        set(userId, applicationId, LAST_TENANT_KEY,
                objectMapper.createObjectNode().put("tenantPid", tenantPid), 1);
    }

    private void validateCoordinates(Long userId, Long applicationId, String key) {
        if (userId == null || applicationId == null) {
            throw new IllegalArgumentException("userId and applicationId are required");
        }
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid application preference key");
        }
    }
}
