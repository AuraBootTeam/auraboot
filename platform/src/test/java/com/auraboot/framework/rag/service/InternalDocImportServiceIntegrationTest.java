package com.auraboot.framework.rag.service;

import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rag.dto.KbDocumentDTO;
import com.auraboot.framework.rag.dto.KnowledgeBaseDTO;
import com.auraboot.framework.rag.entity.KbChunk;
import com.auraboot.framework.rag.service.InternalDocImportService.ImportResult;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for InternalDocImportService — batch import markdown files into RAG.
 * Mocks: EmbeddingService (external API), FileService (file storage).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InternalDocImportServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private InternalDocImportService importService;

    @Autowired
    private KnowledgeBaseService kbService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private EmbeddingService embeddingService;

    @MockBean
    private FileService fileService;

    // =========================================================================
    // Full import
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("IMP-01: Import directory of markdown files")
    void importDocs_basic() throws Exception {
        Path tmpDir = createTestDocsDir();
        setupEmbeddingMock();

        ImportResult result = importService.importDocs(
                getTestTenant().getId(), getTestUser().getId(), tmpDir.toString());

        assertThat(result.totalFiles()).isEqualTo(3);
        assertThat(result.imported()).isEqualTo(3);
        assertThat(result.skipped()).isZero();
        assertThat(result.updated()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.kbPid()).isNotBlank();

        // Verify KB created with expected name
        KnowledgeBaseDTO kb = kbService.getKnowledgeBase(
                getTestTenant().getId(), result.kbPid());
        assertThat(kb).isNotNull();
        assertThat(kb.getName()).isEqualTo("AuraBoot System Documentation");

        // Verify documents stored
        List<KbDocumentDTO> docs = kbService.listDocuments(result.kbPid());
        assertThat(docs).hasSize(3);

        // Verify chunks created
        for (KbDocumentDTO doc : docs) {
            List<KbChunk> chunks = kbService.listChunks(doc.getPid(), 50);
            assertThat(chunks).isNotEmpty();
        }

        cleanupTmpDir(tmpDir);
    }

    @Test
    @Order(2)
    @DisplayName("IMP-02: Incremental import skips unchanged files")
    void importDocs_incremental() throws Exception {
        Path tmpDir = createTestDocsDir();
        setupEmbeddingMock();

        // First import
        ImportResult first = importService.importDocs(
                getTestTenant().getId(), getTestUser().getId(), tmpDir.toString());
        assertThat(first.imported()).isEqualTo(3);

        // Second import (same content)
        ImportResult second = importService.importDocs(
                getTestTenant().getId(), getTestUser().getId(), tmpDir.toString());
        assertThat(second.skipped()).isEqualTo(3);
        assertThat(second.imported()).isZero();
        assertThat(second.updated()).isZero();

        cleanupTmpDir(tmpDir);
    }

    @Test
    @Order(3)
    @DisplayName("IMP-03: Modified file is re-imported")
    void importDocs_update() throws Exception {
        Path tmpDir = createTestDocsDir();
        setupEmbeddingMock();

        // First import
        ImportResult first = importService.importDocs(
                getTestTenant().getId(), getTestUser().getId(), tmpDir.toString());
        assertThat(first.imported()).isEqualTo(3);

        // Modify one file
        Path subDir = tmpDir.resolve("sub");
        Files.writeString(subDir.resolve("architecture.md"),
                "# Updated Architecture\n\nNew content after modification.");

        // Second import
        ImportResult second = importService.importDocs(
                getTestTenant().getId(), getTestUser().getId(), tmpDir.toString());
        assertThat(second.updated()).isEqualTo(1);
        assertThat(second.skipped()).isEqualTo(2);

        cleanupTmpDir(tmpDir);
    }

    @Test
    @Order(4)
    @DisplayName("IMP-04: Metadata preserves file path")
    void importDocs_metadata() throws Exception {
        Path tmpDir = createTestDocsDir();
        setupEmbeddingMock();

        ImportResult result = importService.importDocs(
                getTestTenant().getId(), getTestUser().getId(), tmpDir.toString());

        // Check that doc names contain relative paths
        List<KbDocumentDTO> docs = kbService.listDocuments(result.kbPid());
        List<String> docNames = docs.stream().map(KbDocumentDTO::getDocName).toList();

        assertThat(docNames).anyMatch(n -> n.contains("overview.md"));
        assertThat(docNames).anyMatch(n -> n.contains("architecture.md"));

        // Check chunk metadata has filePath
        KbDocumentDTO firstDoc = docs.get(0);
        List<KbChunk> chunks = kbService.listChunks(firstDoc.getPid(), 1);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).getMetadata()).isNotNull();
        assertThat(chunks.get(0).getMetadata()).contains("filePath");

        cleanupTmpDir(tmpDir);
    }

    // =========================================================================
    // Error handling
    // =========================================================================

    @Test
    @Order(10)
    @DisplayName("IMP-05: Non-existent directory throws IllegalArgumentException")
    void importDocs_invalidDir() {
        assertThatThrownBy(() ->
                importService.importDocs(getTestTenant().getId(), getTestUser().getId(),
                        "/tmp/non-existent-dir-" + System.currentTimeMillis()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a directory");
    }

    @Test
    @Order(11)
    @DisplayName("IMP-06: Empty directory produces zero imports")
    void importDocs_emptyDir() throws Exception {
        Path tmpDir = Files.createTempDirectory("rag-empty-");

        ImportResult result = importService.importDocs(
                getTestTenant().getId(), getTestUser().getId(), tmpDir.toString());

        assertThat(result.totalFiles()).isZero();
        assertThat(result.imported()).isZero();

        cleanupTmpDir(tmpDir);
    }

    @Test
    @Order(12)
    @DisplayName("IMP-07: Embedding failure does not block import")
    void importDocs_embeddingFails() throws Exception {
        Path tmpDir = createTestDocsDir();
        when(embeddingService.embedBatch(anyLong(), anyList(), anyString()))
                .thenThrow(new RuntimeException("API unavailable"));

        ImportResult result = importService.importDocs(
                getTestTenant().getId(), getTestUser().getId(), tmpDir.toString());

        // Import should succeed (chunks stored without vectors)
        assertThat(result.imported()).isEqualTo(3);
        assertThat(result.failed()).isZero();

        cleanupTmpDir(tmpDir);
    }

    @Test
    @Order(13)
    @DisplayName("IMP-08: INDEX.md is excluded from import")
    void importDocs_excludesIndex() throws Exception {
        Path tmpDir = Files.createTempDirectory("rag-index-");
        Files.writeString(tmpDir.resolve("01-test.md"), "# Test\n\nContent.");
        Files.writeString(tmpDir.resolve("INDEX.md"), "# Index\n\nNot imported.");
        setupEmbeddingMock();

        ImportResult result = importService.importDocs(
                getTestTenant().getId(), getTestUser().getId(), tmpDir.toString());

        assertThat(result.totalFiles()).isEqualTo(1);
        List<KbDocumentDTO> docs = kbService.listDocuments(result.kbPid());
        assertThat(docs).noneMatch(d -> d.getDocName().contains("index"));

        cleanupTmpDir(tmpDir);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Path createTestDocsDir() throws Exception {
        Path tmpDir = Files.createTempDirectory("rag-import-test-");
        // Create subdirectory structure mimicking docs/system-reference
        Path subDir = tmpDir.resolve("sub");
        Files.createDirectories(subDir);

        Files.writeString(tmpDir.resolve("overview.md"),
                "# System Overview\n\nAuraBoot is a low-code platform.\n\n## Features\n\nPlugin architecture, DSL engine, AI integration.");
        Files.writeString(subDir.resolve("architecture.md"),
                "# Architecture\n\nModular design with Spring Boot.\n\n## Components\n\nMyBatis Plus, PostgreSQL, Redis.");
        Files.writeString(subDir.resolve("commands.md"),
                "# Command System\n\n20-stage execution pipeline.\n\n## Stages\n\nPRE_VALIDATE, VALIDATE, EXECUTE, POST_EXECUTE.");

        return tmpDir;
    }

    private void setupEmbeddingMock() {
        when(embeddingService.embedBatch(anyLong(), anyList(), anyString()))
                .thenAnswer(invocation -> {
                    List<String> texts = invocation.getArgument(1);
                    return texts.stream().map(t -> {
                        float[] emb = new float[1536];
                        for (int i = 0; i < 1536; i++) {
                            emb[i] = (float) Math.random() * 0.1f;
                        }
                        return emb;
                    }).toList();
                });
    }

    private void cleanupTmpDir(Path dir) throws Exception {
        if (dir != null && Files.exists(dir)) {
            Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception e) {} });
        }
    }
}
