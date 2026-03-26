package com.auraboot.framework.plugin.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PluginManifestDependencyTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void deserialize_old_string_format() throws Exception {
        String json = """
                {
                  "pluginId": "com.test.plugin",
                  "namespace": "test",
                  "version": "1.0.0",
                  "dependencies": ["com.auraboot.org-management", "com.auraboot.pcba-base"]
                }
                """;

        PluginManifest manifest = objectMapper.readValue(json, PluginManifest.class);

        assertEquals(List.of("com.auraboot.org-management", "com.auraboot.pcba-base"),
                manifest.getDependencies());

        List<PluginManifest.PluginDependencySpec> specs = manifest.getEffectiveDependencySpecs();
        assertEquals(2, specs.size());
        assertEquals("com.auraboot.org-management", specs.get(0).getPluginId());
        assertEquals("*", specs.get(0).getVersionRange());
        assertEquals("com.auraboot.pcba-base", specs.get(1).getPluginId());
        assertEquals("*", specs.get(1).getVersionRange());
    }

    @Test
    void deserialize_new_object_format() throws Exception {
        String json = """
                {
                  "pluginId": "com.test.plugin",
                  "namespace": "test",
                  "version": "1.0.0",
                  "dependencies": [
                    {"pluginId": "com.auraboot.org-management", "version": ">=1.0.0"},
                    {"pluginId": "com.auraboot.pcba-base", "version": "^1.2.0"}
                  ]
                }
                """;

        PluginManifest manifest = objectMapper.readValue(json, PluginManifest.class);

        // Plain dependencies list should still contain IDs
        assertEquals(List.of("com.auraboot.org-management", "com.auraboot.pcba-base"),
                manifest.getDependencies());

        // Structured specs should have version constraints
        List<PluginManifest.PluginDependencySpec> specs = manifest.getEffectiveDependencySpecs();
        assertEquals(2, specs.size());
        assertEquals(">=1.0.0", specs.get(0).getVersionRange());
        assertEquals("^1.2.0", specs.get(1).getVersionRange());
    }

    @Test
    void deserialize_mixed_format() throws Exception {
        String json = """
                {
                  "pluginId": "com.test.plugin",
                  "namespace": "test",
                  "version": "1.0.0",
                  "dependencies": [
                    "com.auraboot.org-management",
                    {"pluginId": "com.auraboot.pcba-base", "version": ">=1.0.0"}
                  ]
                }
                """;

        PluginManifest manifest = objectMapper.readValue(json, PluginManifest.class);

        List<PluginManifest.PluginDependencySpec> specs = manifest.getEffectiveDependencySpecs();
        assertEquals(2, specs.size());
        assertEquals("*", specs.get(0).getVersionRange());
        assertEquals(">=1.0.0", specs.get(1).getVersionRange());
    }

    @Test
    void deserialize_null_dependencies() throws Exception {
        String json = """
                {
                  "pluginId": "com.test.plugin",
                  "namespace": "test",
                  "version": "1.0.0"
                }
                """;

        PluginManifest manifest = objectMapper.readValue(json, PluginManifest.class);
        assertNull(manifest.getDependencies());
        assertTrue(manifest.getEffectiveDependencySpecs().isEmpty());
    }

    @Test
    void serialize_preserves_string_format() throws Exception {
        String json = """
                {
                  "pluginId": "com.test.plugin",
                  "namespace": "test",
                  "version": "1.0.0",
                  "dependencies": ["com.auraboot.org-management"]
                }
                """;

        PluginManifest manifest = objectMapper.readValue(json, PluginManifest.class);
        String serialized = objectMapper.writeValueAsString(manifest);

        // Re-deserialize to verify round-trip
        PluginManifest roundTripped = objectMapper.readValue(serialized, PluginManifest.class);
        assertEquals(List.of("com.auraboot.org-management"), roundTripped.getDependencies());
        // Serialized JSON should contain plain string array, not objects
        assertTrue(serialized.contains("\"dependencies\":[\"com.auraboot.org-management\"]"));
    }

    @Test
    void new_fields_have_defaults() throws Exception {
        String json = """
                {
                  "pluginId": "com.test.plugin",
                  "namespace": "test",
                  "version": "1.0.0"
                }
                """;

        PluginManifest manifest = objectMapper.readValue(json, PluginManifest.class);
        assertEquals(1, manifest.getEffectiveDslVersion());
        assertEquals("config", manifest.getEffectivePluginType());
    }

    @Test
    void new_fields_when_provided() throws Exception {
        String json = """
                {
                  "pluginId": "com.test.plugin",
                  "namespace": "test",
                  "version": "1.0.0",
                  "dslVersion": 2,
                  "pluginType": "solution"
                }
                """;

        PluginManifest manifest = objectMapper.readValue(json, PluginManifest.class);
        assertEquals(2, manifest.getEffectiveDslVersion());
        assertEquals("solution", manifest.getEffectivePluginType());
    }

    @Test
    void builder_with_dependencySpecs() {
        PluginManifest manifest = PluginManifest.builder()
                .pluginId("com.test.plugin")
                .namespace("test")
                .version("1.0.0")
                .dependencySpecs(List.of(
                        new PluginManifest.PluginDependencySpec("com.dep.a", ">=1.0.0"),
                        new PluginManifest.PluginDependencySpec("com.dep.b", "*")
                ))
                .build();

        assertEquals(List.of("com.dep.a", "com.dep.b"), manifest.getDependencies());
        assertEquals(">=1.0.0", manifest.getEffectiveDependencySpecs().get(0).getVersionRange());
    }
}
