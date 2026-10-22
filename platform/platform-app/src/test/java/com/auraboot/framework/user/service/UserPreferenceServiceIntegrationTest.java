package com.auraboot.framework.user.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * UserPreferenceService integration tests.
 *
 * <p>Covers:
 * <ul>
 *   <li>UP-01: setPreference persists a string value</li>
 *   <li>UP-02: getPreference retrieves the stored value</li>
 *   <li>UP-03: setPreference overwrites previous value</li>
 *   <li>UP-04: getPreference for unknown key returns null</li>
 *   <li>UP-05: setPreference with JSON object value</li>
 * </ul>
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserPreferenceServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserPreferenceService userPreferenceService;

    @Autowired
    private ObjectMapper objectMapper;

    private final String KEY = "user.pref." + System.currentTimeMillis();

    // ==================== UP-01: set string ====================

    @Test
    @Order(1)
    @DisplayName("UP-01: setPreference persists a string value")
    void setPreference_persistsStringValue() throws Exception {
        JsonNode value = objectMapper.readTree("\"hello-user-pref\"");

        assertThatCode(() ->
                userPreferenceService.setPreference(getTestUser().getId(), KEY, value))
                .doesNotThrowAnyException();

        log.info("UP-01: set user preference key={}", KEY);
    }

    @Test
    @Order(2)
    @DisplayName("UP-02: getPreference retrieves the stored value")
    void getPreference_retrievesStoredValue() {
        JsonNode retrieved = userPreferenceService.getPreference(getTestUser().getId(), KEY);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.asText()).isEqualTo("hello-user-pref");
    }

    @Test
    @Order(3)
    @DisplayName("UP-03: setPreference overwrites previous value")
    void setPreference_overwritesPreviousValue() throws Exception {
        JsonNode newValue = objectMapper.readTree("\"updated-user-pref\"");

        userPreferenceService.setPreference(getTestUser().getId(), KEY, newValue);

        JsonNode retrieved = userPreferenceService.getPreference(getTestUser().getId(), KEY);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.asText()).isEqualTo("updated-user-pref");
    }

    @Test
    @Order(4)
    @DisplayName("UP-04: getPreference for unknown key returns null")
    void getPreference_unknownKey_returnsNull() {
        JsonNode retrieved = userPreferenceService.getPreference(
                getTestUser().getId(), "nonexistent.pref.xyz");

        assertThat(retrieved).isNull();
    }

    @Test
    @Order(5)
    @DisplayName("UP-05: setPreference with JSON object value")
    void setPreference_jsonObject_persistsCorrectly() throws Exception {
        String objectKey = "user.obj." + System.currentTimeMillis();
        JsonNode objValue = objectMapper.readTree("{\"theme\": \"dark\", \"lang\": \"zh-CN\"}");

        userPreferenceService.setPreference(getTestUser().getId(), objectKey, objValue);

        JsonNode retrieved = userPreferenceService.getPreference(getTestUser().getId(), objectKey);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.get("theme").asText()).isEqualTo("dark");
        assertThat(retrieved.get("lang").asText()).isEqualTo("zh-CN");
    }

    @Test
    @Order(10)
    @DisplayName("UP-10: getPreferencesByPrefix returns matching entries")
    void getPreferencesByPrefix_returnsMatchingEntries() throws Exception {
        String prefix = "ui.test." + System.currentTimeMillis() + ".";
        JsonNode tz = objectMapper.readTree("\"Asia/Shanghai\"");
        JsonNode fmt = objectMapper.readTree("\"YYYY-MM-DD\"");

        userPreferenceService.setPreference(getTestUser().getId(), prefix + "timezone", tz);
        userPreferenceService.setPreference(getTestUser().getId(), prefix + "date.format", fmt);

        Map<String, JsonNode> result = userPreferenceService.getPreferencesByPrefix(
                getTestUser().getId(), prefix);

        assertThat(result).hasSize(2);
        assertThat(result.get(prefix + "timezone").asText()).isEqualTo("Asia/Shanghai");
        assertThat(result.get(prefix + "date.format").asText()).isEqualTo("YYYY-MM-DD");
    }

    @Test
    @Order(11)
    @DisplayName("UP-11: getPreferencesByPrefix returns empty map when no matches")
    void getPreferencesByPrefix_noMatches_returnsEmptyMap() {
        Map<String, JsonNode> result = userPreferenceService.getPreferencesByPrefix(
                getTestUser().getId(), "nonexistent.prefix.");

        assertThat(result).isEmpty();
    }
}
