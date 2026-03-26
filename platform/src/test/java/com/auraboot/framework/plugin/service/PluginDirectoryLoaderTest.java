package com.auraboot.framework.plugin.service;

import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.service.impl.PluginDirectoryLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for PluginDirectoryLoader.
 * Tests directory-based plugin loading including single-object files,
 * array fallback, mixed mode, empty directories, and error handling.
 */
@DisplayName("PluginDirectoryLoader Unit Tests")
class PluginDirectoryLoaderTest {

    private PluginDirectoryLoader loader;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        loader = new PluginDirectoryLoader();
    }

    // ==================== Single Object Files ====================

    @Test
    @DisplayName("Should load single-object JSON files from directory")
    void shouldLoadSingleObjectFilesFromDirectory() throws IOException {
        // Create plugin.json pointing to a models directory
        Path modelsDir = tempDir.resolve("config/models");
        Files.createDirectories(modelsDir);

        writePluginJson(tempDir, "config/models");

        // Create individual model files
        Files.writeString(modelsDir.resolve("model_a.json"),
                """
                {"code": "model_a", "modelType": "entity"}
                """);
        Files.writeString(modelsDir.resolve("model_b.json"),
                """
                {"code": "model_b", "modelType": "entity"}
                """);

        PluginManifestExtended manifest = loader.loadFromDirectory(tempDir);

        assertThat(manifest.getModels())
                .isNotNull()
                .hasSize(2)
                .extracting(ModelDefinitionDTO::getCode)
                .containsExactlyInAnyOrder("model_a", "model_b");
    }

    // ==================== Array Fallback ====================

    @Test
    @DisplayName("Should load array JSON file as fallback")
    void shouldLoadArrayJsonFileFallback() throws IOException {
        writePluginJson(tempDir, "config/models.json");

        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);

        // Write array-format JSON file
        Files.writeString(configDir.resolve("models.json"),
                """
                [
                  {"code": "arr_model_1", "modelType": "entity"},
                  {"code": "arr_model_2", "modelType": "entity"},
                  {"code": "arr_model_3", "modelType": "entity"}
                ]
                """);

        PluginManifestExtended manifest = loader.loadFromDirectory(tempDir);

        assertThat(manifest.getModels())
                .isNotNull()
                .hasSize(3)
                .extracting(ModelDefinitionDTO::getCode)
                .containsExactly("arr_model_1", "arr_model_2", "arr_model_3");
    }

    // ==================== Mixed Mode ====================

    @Test
    @DisplayName("Should load mixed single-object and array files from directory")
    void shouldLoadMixedSingleObjectAndArrayFiles() throws IOException {
        Path modelsDir = tempDir.resolve("config/models");
        Files.createDirectories(modelsDir);

        writePluginJson(tempDir, "config/models");

        // Single object file
        Files.writeString(modelsDir.resolve("01_single.json"),
                """
                {"code": "single_model", "modelType": "entity"}
                """);

        // Array file
        Files.writeString(modelsDir.resolve("02_batch.json"),
                """
                [
                  {"code": "batch_model_1", "modelType": "entity"},
                  {"code": "batch_model_2", "modelType": "entity"}
                ]
                """);

        PluginManifestExtended manifest = loader.loadFromDirectory(tempDir);

        assertThat(manifest.getModels())
                .isNotNull()
                .hasSize(3)
                .extracting(ModelDefinitionDTO::getCode)
                .containsExactlyInAnyOrder("single_model", "batch_model_1", "batch_model_2");
    }

    // ==================== Empty Directory ====================

    @Test
    @DisplayName("Should return empty list for empty directory")
    void shouldReturnEmptyListForEmptyDirectory() throws IOException {
        Path modelsDir = tempDir.resolve("config/models");
        Files.createDirectories(modelsDir);

        writePluginJson(tempDir, "config/models");

        PluginManifestExtended manifest = loader.loadFromDirectory(tempDir);

        // Models should be null or empty when directory has no JSON files
        assertThat(manifest.getModels()).isNullOrEmpty();
    }

    @Test
    @DisplayName("Should return empty list when resource path does not exist")
    void shouldReturnEmptyListWhenPathNotExists() throws IOException {
        writePluginJson(tempDir, "config/nonexistent.json");

        PluginManifestExtended manifest = loader.loadFromDirectory(tempDir);

        assertThat(manifest.getModels()).isNullOrEmpty();
    }

    // ==================== Invalid JSON Files ====================

    @Test
    @DisplayName("Should skip invalid JSON files and load valid ones")
    void shouldSkipInvalidJsonAndLoadValidOnes() throws IOException {
        Path modelsDir = tempDir.resolve("config/models");
        Files.createDirectories(modelsDir);

        writePluginJson(tempDir, "config/models");

        // Valid file
        Files.writeString(modelsDir.resolve("01_valid.json"),
                """
                {"code": "valid_model", "modelType": "entity"}
                """);

        // Invalid JSON file
        Files.writeString(modelsDir.resolve("02_invalid.json"),
                "this is not valid json {{{");

        // Another valid file
        Files.writeString(modelsDir.resolve("03_valid.json"),
                """
                {"code": "another_valid", "modelType": "entity"}
                """);

        PluginManifestExtended manifest = loader.loadFromDirectory(tempDir);

        assertThat(manifest.getModels())
                .isNotNull()
                .hasSize(2)
                .extracting(ModelDefinitionDTO::getCode)
                .containsExactly("valid_model", "another_valid");
    }

    // ==================== Validation Tests ====================

    @Test
    @DisplayName("Should throw exception when plugin.json is missing")
    void shouldThrowExceptionWhenPluginJsonMissing() {
        assertThatThrownBy(() -> loader.loadFromDirectory(tempDir))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("plugin.json not found");
    }

    @Test
    @DisplayName("Should throw exception when path is not a directory")
    void shouldThrowExceptionWhenPathIsNotDirectory() throws IOException {
        Path filePath = tempDir.resolve("not-a-dir.txt");
        Files.writeString(filePath, "hello");

        assertThatThrownBy(() -> loader.loadFromDirectory(filePath))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    @DisplayName("isValidPluginDirectory should return true when plugin.json exists")
    void shouldReturnTrueForValidPluginDirectory() throws IOException {
        Files.writeString(tempDir.resolve("plugin.json"), "{}");
        assertThat(loader.isValidPluginDirectory(tempDir)).isTrue();
    }

    @Test
    @DisplayName("isValidPluginDirectory should return false when plugin.json is missing")
    void shouldReturnFalseForInvalidPluginDirectory() {
        assertThat(loader.isValidPluginDirectory(tempDir)).isFalse();
    }

    // ==================== Non-JSON Files ====================

    @Test
    @DisplayName("Should ignore non-JSON files in directory")
    void shouldIgnoreNonJsonFiles() throws IOException {
        Path modelsDir = tempDir.resolve("config/models");
        Files.createDirectories(modelsDir);

        writePluginJson(tempDir, "config/models");

        // JSON file
        Files.writeString(modelsDir.resolve("model.json"),
                """
                {"code": "json_model", "modelType": "entity"}
                """);

        // Non-JSON files (should be ignored)
        Files.writeString(modelsDir.resolve("readme.md"), "# Models");
        Files.writeString(modelsDir.resolve("notes.txt"), "Some notes");
        Files.writeString(modelsDir.resolve(".gitkeep"), "");

        PluginManifestExtended manifest = loader.loadFromDirectory(tempDir);

        assertThat(manifest.getModels())
                .isNotNull()
                .hasSize(1)
                .extracting(ModelDefinitionDTO::getCode)
                .containsExactly("json_model");
    }

    @Test
    @DisplayName("Should load named queries from resourceDirs")
    void shouldLoadNamedQueriesFromResourceDirs() throws IOException {
        Path nqPath = tempDir.resolve("config/named-queries.json");
        Files.createDirectories(nqPath.getParent());

        Files.writeString(tempDir.resolve("plugin.json"), """
                {
                  "pluginId": "com.test.named-query-plugin",
                  "namespace": "ut",
                  "version": "1.0.0",
                  "resourceDirs": {
                    "namedQueries": "config/named-queries.json"
                  }
                }
                """);

        Files.writeString(nqPath, """
                [
                  {
                    "code": "test_named_query",
                    "title": "Test Named Query",
                    "fromSql": "ab_tenant t",
                    "status": "testing",
                    "fields": [
                      {
                        "fieldCode": "tenant_name",
                        "columnExpr": "t.name",
                        "dataType": "string"
                      }
                    ]
                  }
                ]
                """);

        PluginManifestExtended manifest = loader.loadFromDirectory(tempDir);

        assertThat(manifest.getNamedQueries())
                .isNotNull()
                .hasSize(1);
        assertThat(manifest.getNamedQueries().get(0).getCode()).isEqualTo("test_named_query");
        assertThat(manifest.getNamedQueries().get(0).getFields()).hasSize(1);
    }

    // ==================== Helpers ====================

    private void writePluginJson(Path dir, String modelsPath) throws IOException {
        String pluginJson = """
                {
                  "pluginId": "com.test.unit-test",
                  "namespace": "ut",
                  "version": "1.0.0",
                  "displayName": "Unit Test Plugin",
                  "resourceDirs": {
                    "models": "%s"
                  }
                }
                """.formatted(modelsPath);
        Files.writeString(dir.resolve("plugin.json"), pluginJson);
    }
}
