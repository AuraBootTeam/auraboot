package com.auraboot.framework.tenant.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.tenant.service.TenantPreferenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPreferencesControllerTest {

    private final InMemoryTenantPreferenceService tenantPreferenceService = new InMemoryTenantPreferenceService();
    private final SystemPreferencesController controller = new SystemPreferencesController(tenantPreferenceService);

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void getPreferencesReturnsDefaultsAndStatusWhenTimezoneIsNotConfigured() {
        MetaContext.setSystemTenantContext(42L);
        tenantPreferenceService.setPreference(42L, "ui.datetime.format", TextNode.valueOf("YYYY/MM/DD HH:mm"));

        var response = controller.getSystemPreferences();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getDatetimeFormat()).isEqualTo("YYYY/MM/DD HH:mm");
        assertThat(response.getData().getTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(response.getData().isTimezoneConfigured()).isFalse();
        assertThat(response.getData().getTimezoneStatusText()).contains("尚未配置");
    }

    @Test
    void updatePreferencesPersistsDatetimeFormatAndTimezoneForCurrentTenant() {
        MetaContext.setSystemTenantContext(42L);
        var request = new SystemPreferencesController.SystemPreferencesRequest();
        request.setDatetimeFormat("YYYY-MM-DD HH:mm:ss");
        request.setTimezone("Europe/London");

        var response = controller.updateSystemPreferences(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(tenantPreferenceService.getPreference(42L, "ui.datetime.format").asText())
            .isEqualTo("YYYY-MM-DD HH:mm:ss");
        assertThat(tenantPreferenceService.getPreference(42L, "ui.timezone").asText())
            .isEqualTo("Europe/London");
        assertThat(response.getData().isTimezoneConfigured()).isTrue();
        assertThat(response.getData().getTimezoneStatusText()).contains("已设置");
    }

    @Test
    void updatePreferencesClearsStoredValuesWhenRequestValuesAreNull() {
        MetaContext.setSystemTenantContext(42L);
        tenantPreferenceService.setPreference(42L, "ui.datetime.format", TextNode.valueOf("YYYY/MM/DD HH:mm"));
        tenantPreferenceService.setPreference(42L, "ui.timezone", TextNode.valueOf("Europe/London"));
        var request = new SystemPreferencesController.SystemPreferencesRequest();
        request.setDatetimeFormat(null);
        request.setTimezone(null);

        var response = controller.updateSystemPreferences(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(tenantPreferenceService.getPreference(42L, "ui.datetime.format")).isNull();
        assertThat(tenantPreferenceService.getPreference(42L, "ui.timezone")).isNull();
        assertThat(response.getData().getDatetimeFormat()).isEqualTo("YYYY-MM-DD HH:mm:ss");
        assertThat(response.getData().getTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(response.getData().isTimezoneConfigured()).isFalse();
        assertThat(response.getData().getTimezoneStatusText()).contains("尚未配置");
    }

    private static final class InMemoryTenantPreferenceService implements TenantPreferenceService {
        private final Map<Long, Map<String, JsonNode>> store = new HashMap<>();

        @Override
        public JsonNode getPreference(Long tenantId, String key) {
            return store.getOrDefault(tenantId, Map.of()).get(key);
        }

        @Override
        public void setPreference(Long tenantId, String key, JsonNode value) {
            store.computeIfAbsent(tenantId, ignored -> new HashMap<>()).put(key, value);
        }

        @Override
        public void deletePreference(Long tenantId, String key) {
            store.getOrDefault(tenantId, Map.of()).remove(key);
        }

        @Override
        public Map<String, JsonNode> getPreferencesByPrefix(Long tenantId, String prefix) {
            return Map.of();
        }
    }
}
