package com.auraboot.framework.user;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.user.service.UserPreferenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for UserPreferenceService.
 * Covers set/get preference, overwrite, and user-scope isolation.
 * Uses real database, no mocking. Data persists (no rollback).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class UserPreferenceServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserPreferenceService userPreferenceService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String testRunId = String.valueOf(System.currentTimeMillis());
    private final String prefKey = "theme-" + testRunId;

    // ========== Test 1: set then get returns stored value ==========

    @Test
    @Order(1)
    void setPreference_andGet_returnsStoredValue() throws Exception {
        Long userId = getTestUser().getId();
        JsonNode value = objectMapper.readTree("{\"mode\":\"dark\"}");

        userPreferenceService.setPreference(userId, prefKey, value);

        JsonNode retrieved = userPreferenceService.getPreference(userId, prefKey);
        assertNotNull(retrieved, "getPreference must return the stored value");
        assertEquals("dark", retrieved.get("mode").asText(),
                "Retrieved value must match what was stored");
    }

    // ========== Test 2: get non-existent key returns null ==========

    @Test
    @Order(2)
    void getPreference_nonExistentKey_returnsNull() {
        Long userId = getTestUser().getId();
        String neverSetKey = "never-set-" + testRunId;

        JsonNode result = userPreferenceService.getPreference(userId, neverSetKey);

        assertNull(result, "getPreference for a never-set key should return null");
    }

    // ========== Test 3: overwrite returns new value ==========

    @Test
    @Order(3)
    void setPreference_overwrite_returnsNewValue() throws Exception {
        Long userId = getTestUser().getId();
        String overwriteKey = "overwrite-" + testRunId;

        userPreferenceService.setPreference(userId, overwriteKey,
                objectMapper.readTree("{\"v\":1}"));
        userPreferenceService.setPreference(userId, overwriteKey,
                objectMapper.readTree("{\"v\":2}"));

        JsonNode result = userPreferenceService.getPreference(userId, overwriteKey);
        assertNotNull(result);
        assertEquals(2, result.get("v").asInt(),
                "Second setPreference must overwrite the first value");
    }

    // ========== Test 4: preferences are scoped to userId ==========

    @Test
    @Order(4)
    void setPreference_differentUsers_isolated() throws Exception {
        Long userId = getTestUser().getId();
        String isolationKey = "isolation-" + testRunId;

        userPreferenceService.setPreference(userId, isolationKey,
                objectMapper.readTree("{\"owner\":\"user1\"}"));

        // A non-existent user ID should not see this preference
        Long otherUserId = userId + 999999L;
        JsonNode otherResult = userPreferenceService.getPreference(otherUserId, isolationKey);
        assertNull(otherResult,
                "Preference set for userId must not be visible to a different userId");
    }
}
