package com.auraboot.framework.application.release;

import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationBindingCapabilityVerifierTest {
    private static final String RELEASE = "01K6R0YEXAMPLE000000000000";
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String PLUGIN_DIGEST = "sha256:" + "b".repeat(64);

    @SuppressWarnings("unchecked")
    @Test
    void rejectsBindingWhenRequiredRuntimePluginIsMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ApplicationDefinitionResolver definitions = mock(ApplicationDefinitionResolver.class);
        AuraPluginManager plugins = mock(AuraPluginManager.class);
        when(definitions.publishedStableRelease("aura-edu")).thenReturn(release());
        when(jdbc.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class), eq(RELEASE)))
                .thenReturn(List.of(new ApplicationBindingCapabilityVerifier.Component(
                        "com.auraboot.edu-engine", "backend_plugin", PLUGIN_DIGEST)));
        when(plugins.observeLoadedPlugin("com.auraboot.edu-engine")).thenReturn(Optional.empty());

        var verifier = new ApplicationBindingCapabilityVerifier(jdbc, definitions, plugins);
        assertThrows(IllegalStateException.class,
                () -> verifier.requireDeployed("aura-edu", RELEASE, DIGEST));
    }

    @SuppressWarnings("unchecked")
    @Test
    void acceptsExactStartedPluginAndReadableDefinitionBundle() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ApplicationDefinitionResolver definitions = mock(ApplicationDefinitionResolver.class);
        AuraPluginManager plugins = mock(AuraPluginManager.class);
        when(definitions.publishedStableRelease("aura-edu")).thenReturn(release());
        when(jdbc.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class), eq(RELEASE)))
                .thenReturn(List.of(new ApplicationBindingCapabilityVerifier.Component(
                        "com.auraboot.edu-engine", "backend_plugin", PLUGIN_DIGEST)));
        when(plugins.observeLoadedPlugin("com.auraboot.edu-engine")).thenReturn(Optional.of(
                new AuraPluginManager.LoadedPluginArtifact(
                        "com.auraboot.edu-engine", Path.of("edu-engine.jar"), PLUGIN_DIGEST)));

        var verifier = new ApplicationBindingCapabilityVerifier(jdbc, definitions, plugins);
        assertDoesNotThrow(() -> verifier.requireDeployed("aura-edu", RELEASE, DIGEST));
    }

    private static ApplicationDefinitionResolver.ReleaseDefinitions release() {
        return new ApplicationDefinitionResolver.ReleaseDefinitions(
                new ApplicationDefinitionResolver.ReleaseSelection(
                        1L, "aura-edu", RELEASE, DIGEST, "sha256:" + "c".repeat(64), "shadow", 1L),
                List.of());
    }
}
