package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.exception.PluginException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Hermetic contracts for ZIP resource loading, ordering and fail-closed inputs. */
class PluginZipResourceReaderTest {
    private final PluginZipResourceReader reader = new PluginZipResourceReader(new ObjectMapper());

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void preservesInlineResourcesAndLoadsDeclaredFile() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        ModelDefinitionDTO inline = new ModelDefinitionDTO();
        inline.setCode("inline_model");
        manifest.setModels(List.of(inline));
        manifest.setResourceDirs(Map.of("models", "config/models.json"));
        reader.loadResourcesFromZipFiles(manifest,
                Map.of("config/models.json", bytes("[{\"code\":\"zip_model\"}]")));
        assertThat(manifest.getModels()).extracting(ModelDefinitionDTO::getCode)
                .containsExactly("inline_model", "zip_model");
    }

    @Test
    void directoryOrderingIsStableAndNestedFilesAreExcluded() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setResourceDirs(Map.of("models", "config/models"));
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("config/models/z.json", bytes("{\"code\":\"last\"}"));
        files.put("config/models/a.json", bytes("[{\"code\":\"first\"}]"));
        files.put("config/models/nested/hidden.json", bytes("{\"code\":\"hidden\"}"));
        reader.loadResourcesFromZipFiles(manifest, files);
        assertThat(manifest.getModels()).extracting(ModelDefinitionDTO::getCode)
                .containsExactly("first", "last");
    }

    @Test
    void malformedCommandsCannotDisappearBehindSuccessfulImport() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setResourceDirs(Map.of("commands", "config/commands.json"));
        assertThatThrownBy(() -> reader.loadResourcesFromZipFiles(manifest,
                Map.of("config/commands.json", bytes("not-json"))))
                .isInstanceOf(PluginException.class).hasMessageContaining("commands.json");
    }

    @Test
    void declaredPageContributionsAreStrict() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setResourceDirs(Map.of("pageContributions", "config/contributions"));
        assertThatThrownBy(() -> reader.loadResourcesFromZipFiles(manifest,
                Map.of("config/contributions/broken.json", bytes("not-json"))))
                .isInstanceOf(PluginException.class).hasMessageContaining("broken.json");
    }

    @Test
    void semanticPathsCannotTraverseOutsideDeclaredDirectory() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setResourceDirs(Map.of("semantic", "../outside"));
        assertThatThrownBy(() -> reader.loadResourcesFromZipFiles(manifest, Map.of()))
                .isInstanceOf(PluginException.class).hasMessageContaining("Invalid semantic resource path");
    }
}
