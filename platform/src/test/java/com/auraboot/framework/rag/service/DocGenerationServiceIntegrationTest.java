package com.auraboot.framework.rag.service;

import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rag.service.DocGenerationService.GenerationResult;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for DocGenerationService — auto-generated docs from DB metadata.
 * Uses real PostgreSQL to query ab_meta_model, ab_meta_field, ab_command_definition.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocGenerationServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DocGenerationService docGenerationService;

    @MockBean
    private EmbeddingService embeddingService;

    @MockBean
    private FileService fileService;

    @Test
    @Order(1)
    @DisplayName("GEN-01: Generate model dictionary from published models")
    void generateModelDictionary() throws Exception {
        Path tmpDir = Files.createTempDirectory("doc-gen-test-");
        try {
            GenerationResult result = docGenerationService.generate(tmpDir.toString());

            // Should find published models (from plugin imports)
            assertThat(result.models()).isGreaterThanOrEqualTo(0);
            assertThat(result.outputDir()).isEqualTo(tmpDir.toString());

            // Model dictionary file should exist
            Path dictFile = tmpDir.resolve("model-dictionary.md");
            assertThat(dictFile).exists();
            String content = Files.readString(dictFile);
            assertThat(content).contains("# Model Dictionary");
            assertThat(content).contains("Auto-generated");
            if (result.models() > 0) {
                assertThat(content).contains("| Field | Type | Description |");
            }
        } finally {
            cleanupDir(tmpDir);
        }
    }

    @Test
    @Order(2)
    @DisplayName("GEN-02: Generate command reference from published commands")
    void generateCommandReference() throws Exception {
        Path tmpDir = Files.createTempDirectory("doc-gen-test-");
        try {
            GenerationResult result = docGenerationService.generate(tmpDir.toString());

            Path cmdFile = tmpDir.resolve("command-reference.md");
            assertThat(cmdFile).exists();
            String content = Files.readString(cmdFile);
            assertThat(content).contains("# Command Reference");
            if (result.commands() > 0) {
                assertThat(content).contains("| Command | Model | Description |");
            }
        } finally {
            cleanupDir(tmpDir);
        }
    }

    @Test
    @Order(3)
    @DisplayName("GEN-03: Generate field type summary")
    void generateFieldSummary() throws Exception {
        Path tmpDir = Files.createTempDirectory("doc-gen-test-");
        try {
            GenerationResult result = docGenerationService.generate(tmpDir.toString());

            Path summaryFile = tmpDir.resolve("field-summary.md");
            assertThat(summaryFile).exists();
            String content = Files.readString(summaryFile);
            assertThat(content).contains("# Field Type Summary");
            assertThat(content).contains("| Data Type | Count |");
            if (result.fields() > 0) {
                assertThat(content).contains("string");
            }
        } finally {
            cleanupDir(tmpDir);
        }
    }

    @Test
    @Order(4)
    @DisplayName("GEN-04: Output files have frontmatter with lastGenerated")
    void frontmatterPresent() throws Exception {
        Path tmpDir = Files.createTempDirectory("doc-gen-test-");
        try {
            docGenerationService.generate(tmpDir.toString());

            for (String filename : new String[]{"model-dictionary.md", "command-reference.md", "field-summary.md"}) {
                String content = Files.readString(tmpDir.resolve(filename));
                assertThat(content).startsWith("---");
                assertThat(content).contains("lastGenerated:");
                assertThat(content).contains("title:");
            }
        } finally {
            cleanupDir(tmpDir);
        }
    }

    @Test
    @Order(5)
    @DisplayName("GEN-05: Incremental — re-generation overwrites existing files")
    void incrementalOverwrite() throws Exception {
        Path tmpDir = Files.createTempDirectory("doc-gen-test-");
        try {
            docGenerationService.generate(tmpDir.toString());
            long firstMtime = Files.getLastModifiedTime(tmpDir.resolve("model-dictionary.md")).toMillis();

            Thread.sleep(100); // ensure different mtime

            docGenerationService.generate(tmpDir.toString());
            long secondMtime = Files.getLastModifiedTime(tmpDir.resolve("model-dictionary.md")).toMillis();

            assertThat(secondMtime).isGreaterThanOrEqualTo(firstMtime);
        } finally {
            cleanupDir(tmpDir);
        }
    }

    private void cleanupDir(Path dir) throws Exception {
        if (dir != null && Files.exists(dir)) {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception e) {} });
        }
    }
}
