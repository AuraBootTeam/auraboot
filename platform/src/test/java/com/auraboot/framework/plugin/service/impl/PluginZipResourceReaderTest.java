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

    private String publicFixture(String name) throws java.io.IOException {
        try (var stream = getClass().getResourceAsStream("/plugin/public-" + name + "-contract.json")) {
            return new String(java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void publicContractsMergeInlineDeclarationsAndRejectDuplicateCodes() throws java.io.IOException {
        var manifest = new PluginManifestExtended();
        manifest.setResourceDirs(Map.of("publicResources", "config/resources.json", "publicQueries", "config/queries.json"));
        var inline = mapper.readValue(publicFixture("resource").replace("public.records", "public.inline"),
                com.auraboot.framework.openplatform.service.OpenApiResourceContract.class);
        manifest.setPublicResources(List.of(inline));
        reader.loadResourcesFromZipFiles(manifest, Map.of("config/resources.json", bytes("[" + publicFixture("resource") + "]"),
                "config/queries.json", bytes("[" + publicFixture("query") + "]")));
        assertThat(manifest.getPublicResources()).extracting(com.auraboot.framework.openplatform.service.OpenApiResourceContract::code)
                .containsExactly("public.inline", "public.records");
        assertThat(manifest.getPublicQueries().getFirst().sourceResources()).containsEntry("public_record", "public.records");
        assertThat(manifest.validateResources()).isTrue();
        manifest.setPublicResources(List.of(inline, inline));
        assertThat(manifest.validateResources()).isFalse();
        assertThat(manifest.getValidationErrors()).contains("publicResources: unique non-null contracts required");
    }

    @Test
    void malformedAndMissingPublicFilesAbortZipLoading() throws java.io.IOException {
        for (String key : List.of("publicResources", "publicQueries")) {
            var manifest = new PluginManifestExtended();
            manifest.setResourceDirs(Map.of(key, "config/public.json"));
            assertThatThrownBy(() -> reader.loadResourcesFromZipFiles(manifest, Map.of("config/public.json", bytes("not-json"))))
                    .isInstanceOf(PluginException.class).hasMessageContaining("public.json");
            assertThatThrownBy(() -> reader.loadResourcesFromZipFiles(manifest, Map.of()))
                    .isInstanceOf(PluginException.class).hasMessageContaining("missing");
            for (String invalid : List.of("null", "[null]")) {
                assertThatThrownBy(() -> reader.loadResourcesFromZipFiles(manifest, Map.of("config/public.json", bytes(invalid))))
                        .isInstanceOf(PluginException.class).hasMessageContaining("public.json");
                manifest.setResourceDirs(Map.of(key, "config/public"));
                assertThatThrownBy(() -> reader.loadResourcesFromZipFiles(manifest, Map.of("config/public/invalid.json", bytes(invalid))))
                        .isInstanceOf(PluginException.class).hasMessageContaining("invalid.json");
                manifest.setResourceDirs(Map.of(key, "config/public.json"));
            }
        }
    }
    private final ObjectMapper mapper = new ObjectMapper();
    private final PluginZipResourceReader reader = new PluginZipResourceReader(mapper);

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
