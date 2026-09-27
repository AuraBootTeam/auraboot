package com.auraboot.framework.application.release;

import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** Fail-closed runtime check used only by the tenant binding path. */
@Service
public final class ApplicationBindingCapabilityVerifier {
    private final JdbcTemplate jdbc;
    private final ApplicationDefinitionResolver definitions;
    private final AuraPluginManager plugins;

    public ApplicationBindingCapabilityVerifier(JdbcTemplate jdbc,
                                                ApplicationDefinitionResolver definitions,
                                                AuraPluginManager plugins) {
        this.jdbc = jdbc;
        this.definitions = definitions;
        this.plugins = plugins;
    }

    record Component(String key, String type, String digest) {}

    public void requireDeployed(String applicationCode, String releaseId, String releaseDigest) {
        var loaded = definitions.publishedStableRelease(applicationCode);
        require(releaseId.equals(loaded.release().releaseId())
                        && releaseDigest.equals(loaded.release().releaseDigest()),
                "Published stable definition bundle is not deployed");

        List<Component> components = jdbc.query("""
                SELECT component_key,component_type,component_digest
                FROM ab_application_release_component
                WHERE release_id=? ORDER BY component_key
                """, (row, index) -> new Component(row.getString(1), row.getString(2), row.getString(3)), releaseId);
        require(!components.isEmpty(), "Application release has no deployable components");
        for (Component component : components) {
            if (!"backend_plugin".equals(component.type())) continue;
            var actual = plugins.observeLoadedPlugin(component.key())
                    .orElseThrow(() -> new IllegalStateException(
                            "Required application plugin is not started: " + component.key()));
            require(component.digest().equals(actual.digest()),
                    "Loaded application plugin digest differs from the stable release: " + component.key());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
