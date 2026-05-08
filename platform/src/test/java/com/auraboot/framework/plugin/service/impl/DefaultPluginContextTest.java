package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.PluginManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for DefaultPluginContext: verifies builder, accessors, defaults,
 * and mutation methods that track registered/removed resources.
 */
@DisplayName("DefaultPluginContext Unit Tests")
class DefaultPluginContextTest {

    @Test
    @DisplayName("Builder should populate all PluginContext fields")
    void shouldPopulateContextFields() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("k1", "v1");
        settings.put("k2", 42);

        PluginManifest manifest = PluginManifest.builder()
                .pluginId("com.test.plugin")
                .namespace("test")
                .version("1.0.0")
                .build();

        DefaultPluginContext ctx = DefaultPluginContext.builder()
                .tenantId(100L)
                .pluginId("com.test.plugin")
                .namespace("test")
                .version("1.0.0")
                .settings(settings)
                .manifest(manifest)
                .freshInstall(true)
                .previousVersion("0.9.0")
                .wasEnabled(true)
                .preUninstall(true)
                .removeData(true)
                .build();

        assertThat(ctx.getTenantId()).isEqualTo(100L);
        assertThat(ctx.getPluginId()).isEqualTo("com.test.plugin");
        assertThat(ctx.getNamespace()).isEqualTo("test");
        assertThat(ctx.getVersion()).isEqualTo("1.0.0");
        assertThat(ctx.getManifest()).isSameAs(manifest);
        assertThat(ctx.isFreshInstall()).isTrue();
        assertThat(ctx.getPreviousVersion()).isEqualTo("0.9.0");
        assertThat(ctx.wasEnabled()).isTrue();
        assertThat(ctx.isPreUninstall()).isTrue();
        assertThat(ctx.shouldRemoveData()).isTrue();
    }

    @Test
    @DisplayName("Builder defaults should produce safe values when settings is null")
    void shouldHandleNullSettings() {
        DefaultPluginContext ctx = DefaultPluginContext.builder()
                .pluginId("p")
                .build();

        assertThat(ctx.getSettings()).isNotNull().isEmpty();
        assertThat(ctx.<Object>getSetting("missing")).isNull();
        assertThat(ctx.getSetting("missing", "fallback")).isEqualTo("fallback");
        assertThat(ctx.isFreshInstall()).isTrue();
        assertThat(ctx.wasEnabled()).isFalse();
        assertThat(ctx.isPreUninstall()).isFalse();
        assertThat(ctx.shouldRemoveData()).isFalse();
    }

    @Test
    @DisplayName("getSetting should return typed value or default")
    void shouldReturnSettingValueOrDefault() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("name", "alice");
        settings.put("count", 7);

        DefaultPluginContext ctx = DefaultPluginContext.builder()
                .pluginId("p")
                .settings(settings)
                .build();

        String name = ctx.getSetting("name");
        Integer count = ctx.getSetting("count");
        Integer fallback = ctx.getSetting("absent", 99);

        assertThat(name).isEqualTo("alice");
        assertThat(count).isEqualTo(7);
        assertThat(fallback).isEqualTo(99);
    }

    @Test
    @DisplayName("getSettings should return defensive copy")
    void getSettingsShouldReturnDefensiveCopy() {
        Map<String, Object> original = new HashMap<>();
        original.put("k", "v");

        DefaultPluginContext ctx = DefaultPluginContext.builder()
                .pluginId("p")
                .settings(original)
                .build();

        Map<String, Object> snapshot = ctx.getSettings();
        snapshot.put("injected", "bad");

        // Mutating the returned map must not affect the context's internal copy.
        assertThat(ctx.getSettings()).doesNotContainKey("injected").containsEntry("k", "v");
    }

    @Test
    @DisplayName("registerModel/registerCommand should track all registrations")
    void shouldTrackRegistrations() {
        DefaultPluginContext ctx = DefaultPluginContext.builder()
                .pluginId("p")
                .build();

        ctx.registerModel("m1");
        ctx.registerModel("m2");
        ctx.registerCommand("c1");

        assertThat(ctx.getRegisteredModels()).containsExactly("m1", "m2");
        assertThat(ctx.getRegisteredCommands()).containsExactly("c1");
    }

    @Test
    @DisplayName("markModelForRemoval/markCommandForRemoval should track all entries")
    void shouldTrackRemovals() {
        DefaultPluginContext ctx = DefaultPluginContext.builder()
                .pluginId("p")
                .build();

        ctx.markModelForRemoval("m_rm_1");
        ctx.markModelForRemoval("m_rm_2");
        ctx.markCommandForRemoval("c_rm_1");

        assertThat(ctx.getModelsToRemove()).containsExactly("m_rm_1", "m_rm_2");
        assertThat(ctx.getCommandsToRemove()).containsExactly("c_rm_1");
    }

    @Test
    @DisplayName("Logging-only register/unregister methods should not throw")
    void noOpRegistrationMethodsShouldNotThrow() {
        DefaultPluginContext ctx = DefaultPluginContext.builder()
                .pluginId("p")
                .build();

        // These methods only log — covering them ensures no NPE on missing collaborators.
        ctx.registerScheduledTask("task1", "* * * * *", "com.example.Task");
        ctx.registerEventListener("event.x", "com.example.Listener");
        ctx.unregisterScheduledTask("task1");
        ctx.unregisterAllScheduledTasks();
        ctx.unregisterEventListener("event.x", "com.example.Listener");
        ctx.unregisterAllEventListeners();
        ctx.reportProgress(50, "halfway");
        ctx.log("hello");

        // After all calls, registration tracking must remain unchanged.
        assertThat(ctx.getRegisteredModels()).isEmpty();
        assertThat(ctx.getRegisteredCommands()).isEmpty();
        assertThat(ctx.getModelsToRemove()).isEmpty();
        assertThat(ctx.getCommandsToRemove()).isEmpty();
    }
}
