package com.auraboot.framework.plugin.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.PluginResourceOwner;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.service.PluginResourceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginResourceControllerTest {

    @Mock
    private PluginResourceService pluginResourceService;

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void getResourceOwnerMapsServiceViewToApiDto() {
        MetaContext.setContext(7L, 11L, "user-11", "tester");
        Instant importedAt = Instant.parse("2026-07-25T10:15:30Z");
        when(pluginResourceService.findResourceOwner(7L, ResourceType.MODEL, "asset"))
                .thenReturn(new PluginResourceOwner(
                        "com.example.plugin",
                        "Example",
                        "1.2.3",
                        "shared",
                        true,
                        null,
                        importedAt));

        var response = new PluginResourceController(pluginResourceService)
                .getResourceOwner("model", "asset");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().managed()).isTrue();
        assertThat(response.getData().pluginId()).isEqualTo("com.example.plugin");
        assertThat(response.getData().pluginName()).isEqualTo("Example");
        assertThat(response.getData().pluginVersion()).isEqualTo("1.2.3");
        assertThat(response.getData().ownershipType()).isEqualTo("shared");
        assertThat(response.getData().userModified()).isTrue();
        assertThat(response.getData().importedAt()).isEqualTo(importedAt);
        assertThat(response.getData().protectionLevel()).isEqualTo(1);
    }

    @Test
    void exportPluginConfigDelegatesToService() {
        Map<String, List<Map<String, Object>>> exported =
                Map.of("model", List.of(Map.of("code", "asset")));
        when(pluginResourceService.exportPluginConfig("com.example.plugin")).thenReturn(exported);

        var response = new PluginResourceController(pluginResourceService)
                .exportPluginConfig("com.example.plugin");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isSameAs(exported);
    }
}
