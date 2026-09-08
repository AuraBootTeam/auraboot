package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.exception.PluginException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic filesystem coverage IT for {@link PluginDirectoryLoader} — the resource
 * loading surface whose lambda bodies were previously measured as an uncovered
 * `$ListSetter` blob (635 missed lines). Builds a synthetic plugin directory
 * declaring every resourceDirs key, then asserts the loaded manifest carries each
 * resource list, the agent-definitions convention path, and the error paths
 * (missing manifest, non-directory, broken JSON).
 */
@DisplayName("PluginDirectoryLoader Coverage IT — resourceDirs loading + error paths")
class PluginDirectoryLoaderCoverageIT {

    @TempDir
    Path pluginRoot;

    private Path writePlugin(Map<String, String> resourceDirs) throws IOException {
        Path dir = Files.createDirectory(pluginRoot.resolve("my-plugin-" + System.nanoTime()));
        StringBuilder json = new StringBuilder("{");
        json.append("\"pluginId\":\"it-plugin\",");
        json.append("\"version\":\"1.0.0\",");
        if (resourceDirs != null && !resourceDirs.isEmpty()) {
            json.append("\"resourceDirs\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : resourceDirs.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('"').append(e.getKey()).append("\":\"config/").append(e.getKey()).append(".json\"");
                Files.createDirectories(dir.resolve("config"));
                Files.writeString(dir.resolve("config").resolve(e.getKey() + ".json"),
                        "[{\"code\":\"it-" + e.getKey() + "\"}]");
            }
            json.append('}');
        }
        json.append('}');
        Files.writeString(dir.resolve("plugin.json"), json.toString());
        return dir;
    }

    @Test
    @DisplayName("loadFromDirectory loads every declared resourceDirs list into the manifest")
    void loadsEveryResourceKind() throws IOException {
        Map<String, String> dirs = new HashMap<>();
        for (String key : new String[]{
                "models", "fields", "modelFieldBindings", "dicts", "commands", "bindingRules",
                "menus", "permissions", "roles", "pages", "automations", "rules", "processes",
                "namedQueries", "savedViews", "fieldMasks", "eventPolicies", "conditionFragments",
                "decisionDefinitions", "notificationTemplates", "sla", "semantic",
                "pageContributions", "capabilities"}) {
            dirs.put(key, "config/" + key + ".json");
        }
        Path dir = writePlugin(dirs);

        PluginManifestExtended manifest = new PluginDirectoryLoader().loadFromDirectory(dir);

        assertNotNull(manifest);
        assertEquals("it-plugin", manifest.getPluginId());
        assertEquals("1.0.0", manifest.getVersion());
        assertEquals(1, manifest.getModels().size());
        assertEquals(1, manifest.getFields().size());
        assertEquals(1, manifest.getModelFieldBindings().size());
        assertEquals(1, manifest.getDicts().size());
        assertEquals(1, manifest.getCommands().size());
        assertEquals(1, manifest.getMenus().size());
        assertEquals(1, manifest.getPermissions().size());
        assertEquals(1, manifest.getRoles().size());
        assertNotNull(manifest.getResourceCounts());
        assertTrue(manifest.getResourceCounts().size() > 0, "resource counts should be summarised");
    }

    @Test
    @DisplayName("agentDefinitions default convention path is honoured when no key is set")
    void agentDefinitionsConvention() throws IOException {
        Map<String, String> dirs = new HashMap<>();
        dirs.put("models", "config/models.json");
        Path dir = writePlugin(dirs);
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config").resolve("agent-definitions.json"),
                "[{\"agentCode\":\"it_agent\",\"name\":\"IT agent\"}]");

        PluginManifestExtended manifest = new PluginDirectoryLoader().loadFromDirectory(dir);

        assertNotNull(manifest.getAgentDefinitions());
        assertEquals(1, manifest.getAgentDefinitions().size());
        assertEquals("it_agent", manifest.getAgentDefinitions().get(0).getAgentCode());
    }

    @Test
    @DisplayName("isValidPluginDirectory distinguishes manifest presence")
    void validityCheck() throws IOException {
        Path dir = writePlugin(null);
        PluginDirectoryLoader loader = new PluginDirectoryLoader();
        assertTrue(loader.isValidPluginDirectory(dir));
        assertFalse(loader.isValidPluginDirectory(pluginRoot.resolve("does-not-exist")));
    }

    @Test
    @DisplayName("error paths: missing manifest, not a directory, broken manifest JSON")
    void errorPaths() throws IOException {
        PluginDirectoryLoader loader = new PluginDirectoryLoader();

        Path empty = Files.createDirectory(pluginRoot.resolve("empty-" + System.nanoTime()));
        PluginException missing = assertThrows(PluginException.class, () -> loader.loadFromDirectory(empty));
        assertTrue(missing.getMessage().contains("plugin.json not found"));

        Path file = Files.createFile(pluginRoot.resolve("file-" + System.nanoTime()));
        assertThrows(PluginException.class, () -> loader.loadFromDirectory(file));

        Path broken = Files.createDirectory(pluginRoot.resolve("broken-" + System.nanoTime()));
        Files.writeString(broken.resolve("plugin.json"), "{not json");
        assertThrows(PluginException.class, () -> loader.loadFromDirectory(broken));
    }
}
