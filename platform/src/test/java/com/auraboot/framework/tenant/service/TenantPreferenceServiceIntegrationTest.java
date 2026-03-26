package com.auraboot.framework.tenant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

/**
 * TenantPreferenceService integration tests.
 *
 * <p>Covers:
 * <ul>
 *   <li>P1-01: setPreference persists a string value</li>
 *   <li>P1-02: getPreference retrieves the stored value</li>
 *   <li>P1-03: setPreference overwrites previous value</li>
 *   <li>P1-04: getPreference for unknown key returns null</li>
 *   <li>P1-05: setPreference with JSON object value</li>
 * </ul>
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantPreferenceServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TenantPreferenceService tenantPreferenceService;

    @Autowired
    private ObjectMapper objectMapper;

    private final String KEY = "test.pref." + System.currentTimeMillis();

    // ==================== P1-01: set and get string value ====================

    @Test
    @Order(1)
    @DisplayName("P1-01: setPreference persists a string value")
    void setPreference_persistsStringValue() throws Exception {
        JsonNode value = objectMapper.readTree("\"hello-integration\"");

        assertThatCode(() ->
                tenantPreferenceService.setPreference(getTestTenant().getId(), KEY, value))
                .doesNotThrowAnyException();

        log.info("P1-01: set preference key={}", KEY);
    }

    @Test
    @Order(2)
    @DisplayName("P1-02: getPreference retrieves the stored value")
    void getPreference_retrievesStoredValue() throws Exception {
        JsonNode retrieved = tenantPreferenceService.getPreference(getTestTenant().getId(), KEY);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.asText()).isEqualTo("hello-integration");
    }

    @Test
    @Order(3)
    @DisplayName("P1-03: setPreference overwrites previous value")
    void setPreference_overwritesPreviousValue() throws Exception {
        JsonNode newValue = objectMapper.readTree("\"updated-value\"");

        tenantPreferenceService.setPreference(getTestTenant().getId(), KEY, newValue);

        JsonNode retrieved = tenantPreferenceService.getPreference(getTestTenant().getId(), KEY);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.asText()).isEqualTo("updated-value");
    }

    @Test
    @Order(4)
    @DisplayName("P1-04: getPreference for unknown key returns null")
    void getPreference_unknownKey_returnsNull() {
        JsonNode retrieved = tenantPreferenceService.getPreference(
                getTestTenant().getId(), "nonexistent.key.xyz");

        assertThat(retrieved).isNull();
    }

    @Test
    @Order(5)
    @DisplayName("P1-05: setPreference with nested JSON object persists correctly")
    void setPreference_jsonObject_persistsCorrectly() throws Exception {
        String objectKey = "test.obj." + System.currentTimeMillis();
        JsonNode objValue = objectMapper.readTree("{\"theme\": \"dark\", \"locale\": \"zh-CN\"}");

        tenantPreferenceService.setPreference(getTestTenant().getId(), objectKey, objValue);

        JsonNode retrieved = tenantPreferenceService.getPreference(getTestTenant().getId(), objectKey);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.get("theme").asText()).isEqualTo("dark");
        assertThat(retrieved.get("locale").asText()).isEqualTo("zh-CN");
    }
}
