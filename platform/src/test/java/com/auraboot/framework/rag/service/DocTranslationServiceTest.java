package com.auraboot.framework.rag.service;

import com.auraboot.framework.rag.service.DocTranslationService.TranslationStatusReport;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for DocTranslationService — multi-language doc pipeline.
 * Pure filesystem tests, no Spring context needed.
 */
class DocTranslationServiceTest {

    private final DocTranslationService service = new DocTranslationService();

    @Test
    @DisplayName("TR-01: Check status with no translations returns all MISSING")
    void checkStatus_allMissing() throws Exception {
        Path tmpDir = createTestDocs();
        try {
            TranslationStatusReport report = service.checkStatus(tmpDir.toString(), tmpDir.toString());

            assertThat(report.sourceFileCount()).isEqualTo(2);
            assertThat(report.targetLocales()).containsExactly("en-US", "ja-JP", "ko-KR");
            assertThat(report.totalTranslations()).isEqualTo(6); // 2 files × 3 locales
            assertThat(report.missing()).isEqualTo(6);
            assertThat(report.upToDate()).isZero();
            assertThat(report.stale()).isZero();
        } finally {
            cleanupDir(tmpDir);
        }
    }

    @Test
    @DisplayName("TR-02: Generate stubs creates placeholder files")
    void generateStubs() throws Exception {
        Path tmpDir = createTestDocs();
        try {
            int created = service.generateStubs(tmpDir.toString(), tmpDir.toString());

            assertThat(created).isEqualTo(6); // 2 files × 3 locales

            // Verify en-US stub exists
            Path enStub = tmpDir.resolve("en-US/01-overview.md");
            assertThat(enStub).exists();
            String content = Files.readString(enStub);
            assertThat(content).contains("translationStatus: DRAFT");
            assertThat(content).contains("translatedFrom: zh-CN");
            assertThat(content).contains("sourceHash:");
            assertThat(content).contains("locale: en-US");

            // Verify ja-JP stub exists
            assertThat(tmpDir.resolve("ja-JP/01-overview.md")).exists();
            // Verify ko-KR stub exists
            assertThat(tmpDir.resolve("ko-KR/01-overview.md")).exists();
        } finally {
            cleanupDir(tmpDir);
        }
    }

    @Test
    @DisplayName("TR-03: After generating stubs, status shows UP_TO_DATE")
    void checkStatus_afterStubs() throws Exception {
        Path tmpDir = createTestDocs();
        try {
            service.generateStubs(tmpDir.toString(), tmpDir.toString());
            TranslationStatusReport report = service.checkStatus(tmpDir.toString(), tmpDir.toString());

            assertThat(report.upToDate()).isEqualTo(6);
            assertThat(report.missing()).isZero();
            assertThat(report.stale()).isZero();
        } finally {
            cleanupDir(tmpDir);
        }
    }

    @Test
    @DisplayName("TR-04: Modified source marks translations as STALE")
    void checkStatus_staleAfterEdit() throws Exception {
        Path tmpDir = createTestDocs();
        try {
            service.generateStubs(tmpDir.toString(), tmpDir.toString());

            // Modify source file
            Files.writeString(tmpDir.resolve("01-overview.md"),
                    "# Updated Overview\n\nNew content that changes the hash.");

            TranslationStatusReport report = service.checkStatus(tmpDir.toString(), tmpDir.toString());

            // 01-overview.md translations should be STALE (3), 02-commands.md still UP_TO_DATE (3)
            assertThat(report.stale()).isEqualTo(3);
            assertThat(report.upToDate()).isEqualTo(3);
        } finally {
            cleanupDir(tmpDir);
        }
    }

    @Test
    @DisplayName("TR-05: Re-running generateStubs skips existing translations")
    void generateStubs_idempotent() throws Exception {
        Path tmpDir = createTestDocs();
        try {
            int first = service.generateStubs(tmpDir.toString(), tmpDir.toString());
            assertThat(first).isEqualTo(6);

            int second = service.generateStubs(tmpDir.toString(), tmpDir.toString());
            assertThat(second).isZero(); // All already exist
        } finally {
            cleanupDir(tmpDir);
        }
    }

    @Test
    @DisplayName("TR-06: Invalid source directory throws exception")
    void checkStatus_invalidDir() {
        assertThatThrownBy(() ->
                service.checkStatus("/tmp/nonexistent-" + System.currentTimeMillis(), "/tmp"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TR-07: INDEX.md is excluded from translation")
    void generateStubs_excludesIndex() throws Exception {
        Path tmpDir = createTestDocs();
        Files.writeString(tmpDir.resolve("INDEX.md"), "# Index\n\nNot translated.");
        try {
            int created = service.generateStubs(tmpDir.toString(), tmpDir.toString());
            assertThat(created).isEqualTo(6); // Only 2 real docs × 3 locales, INDEX excluded

            assertThat(tmpDir.resolve("en-US/INDEX.md")).doesNotExist();
        } finally {
            cleanupDir(tmpDir);
        }
    }

    @Test
    @DisplayName("TR-08: Stub content includes source text for translation reference")
    void generateStubs_includesSourceText() throws Exception {
        Path tmpDir = createTestDocs();
        try {
            service.generateStubs(tmpDir.toString(), tmpDir.toString());

            String stubContent = Files.readString(tmpDir.resolve("en-US/01-overview.md"));
            assertThat(stubContent).contains("System Overview"); // source title
            assertThat(stubContent).contains("AuraBoot"); // source content
        } finally {
            cleanupDir(tmpDir);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Path createTestDocs() throws Exception {
        Path tmpDir = Files.createTempDirectory("doc-translate-test-");
        Files.writeString(tmpDir.resolve("01-overview.md"),
                "# System Overview\n\nAuraBoot is a low-code platform.\n\n## Features\n\nPlugin architecture.");
        Files.writeString(tmpDir.resolve("02-commands.md"),
                "# Command System\n\n20-stage execution pipeline.\n\n## Stages\n\nValidate, execute, post.");
        return tmpDir;
    }

    private void cleanupDir(Path dir) throws Exception {
        if (dir != null && Files.exists(dir)) {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (Exception e) {} });
        }
    }
}
