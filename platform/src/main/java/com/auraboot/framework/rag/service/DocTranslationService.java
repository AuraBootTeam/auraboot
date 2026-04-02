package com.auraboot.framework.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;

/**
 * Multi-language documentation pipeline.
 * Tracks source doc changes, detects stale translations, and generates translation status reports.
 * Actual LLM translation is deferred — this service provides the infrastructure for tracking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocTranslationService {

    private static final List<String> TARGET_LOCALES = List.of("en-US", "ja-JP", "ko-KR");
    private static final String SOURCE_LOCALE = "zh-CN";

    /**
     * Scan source docs and check translation status for all target locales.
     *
     * @param sourceDir  source docs directory (e.g., docs/system-reference)
     * @param translateBaseDir  base directory for translations (e.g., docs/system-reference)
     * @return status report
     */
    public TranslationStatusReport checkStatus(String sourceDir, String translateBaseDir) throws IOException {
        Path sourcePath = Path.of(sourceDir);
        Path basePath = Path.of(translateBaseDir);

        if (!Files.isDirectory(sourcePath)) {
            throw new IllegalArgumentException("Source directory not found: " + sourceDir);
        }

        List<Path> sourceFiles = collectMarkdownFiles(sourcePath);
        List<DocTranslationStatus> statuses = new ArrayList<>();
        int total = 0, translated = 0, stale = 0, missing = 0;

        for (Path sourceFile : sourceFiles) {
            String relativePath = sourcePath.relativize(sourceFile).toString();
            String sourceHash = hashFile(sourceFile);

            for (String locale : TARGET_LOCALES) {
                total++;
                Path translatedFile = basePath.resolve(locale).resolve(relativePath);

                if (!Files.exists(translatedFile)) {
                    statuses.add(new DocTranslationStatus(relativePath, locale, "missing", null, sourceHash));
                    missing++;
                } else {
                    // Check if translation is stale by comparing source hash in frontmatter
                    String translatedContent = Files.readString(translatedFile);
                    String savedSourceHash = extractFrontmatterValue(translatedContent, "sourceHash");

                    if (sourceHash.equals(savedSourceHash)) {
                        translated++;
                        statuses.add(new DocTranslationStatus(relativePath, locale, "up_to_date", savedSourceHash, sourceHash));
                    } else {
                        stale++;
                        statuses.add(new DocTranslationStatus(relativePath, locale, "stale", savedSourceHash, sourceHash));
                    }
                }
            }
        }

        return new TranslationStatusReport(
                sourceFiles.size(), TARGET_LOCALES, total, translated, stale, missing, statuses);
    }

    /**
     * Generate translation stub files for missing translations.
     * Creates placeholder .md files with frontmatter metadata for human review.
     *
     * @param sourceDir       source docs directory
     * @param translateBaseDir base directory for translations
     * @return number of stub files created
     */
    public int generateStubs(String sourceDir, String translateBaseDir) throws IOException {
        Path sourcePath = Path.of(sourceDir);
        Path basePath = Path.of(translateBaseDir);
        int created = 0;

        List<Path> sourceFiles = collectMarkdownFiles(sourcePath);

        for (Path sourceFile : sourceFiles) {
            String relativePath = sourcePath.relativize(sourceFile).toString();
            String sourceHash = hashFile(sourceFile);
            String sourceContent = Files.readString(sourceFile);
            String title = extractTitle(sourceContent);

            for (String locale : TARGET_LOCALES) {
                Path translatedFile = basePath.resolve(locale).resolve(relativePath);

                if (!Files.exists(translatedFile)) {
                    Files.createDirectories(translatedFile.getParent());

                    StringBuilder stub = new StringBuilder();
                    stub.append("---\n");
                    stub.append("title: ").append(title).append("\n");
                    stub.append("translatedFrom: ").append(SOURCE_LOCALE).append("\n");
                    stub.append("translationStatus: DRAFT\n");
                    stub.append("sourceHash: ").append(sourceHash).append("\n");
                    stub.append("sourceFile: ").append(relativePath).append("\n");
                    stub.append("locale: ").append(locale).append("\n");
                    stub.append("generatedAt: ").append(LocalDate.now()).append("\n");
                    stub.append("---\n\n");
                    stub.append("<!-- TODO: Translate from ").append(SOURCE_LOCALE).append(" -->\n");
                    stub.append("<!-- Source: ").append(relativePath).append(" -->\n\n");
                    stub.append(sourceContent);

                    Files.writeString(translatedFile, stub.toString());
                    created++;
                }
            }
        }

        log.info("Generated {} translation stubs for {} locales", created, TARGET_LOCALES.size());
        return created;
    }

    private List<Path> collectMarkdownFiles(Path basePath) throws IOException {
        try (Stream<Path> walk = Files.walk(basePath)) {
            return walk.filter(p -> !Files.isDirectory(p))
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals("INDEX.md"))
                    .filter(p -> !p.toString().contains("/en-US/") && !p.toString().contains("/ja-JP/") && !p.toString().contains("/ko-KR/"))
                    .sorted()
                    .toList();
        }
    }

    private String hashFile(Path file) {
        try {
            byte[] content = Files.readAllBytes(file);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(content);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.substring(0, 16); // short hash
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String extractTitle(String content) {
        for (String line : content.split("\n")) {
            if (line.startsWith("# ")) {
                return line.substring(2).trim();
            }
        }
        return "Untitled";
    }

    private String extractFrontmatterValue(String content, String key) {
        if (!content.startsWith("---")) return null;
        int end = content.indexOf("---", 3);
        if (end < 0) return null;
        String frontmatter = content.substring(3, end);
        for (String line : frontmatter.split("\n")) {
            if (line.startsWith(key + ":")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return null;
    }

    public record DocTranslationStatus(
            String sourceFile, String locale, String status,
            String translatedHash, String currentSourceHash) {}

    public record TranslationStatusReport(
            int sourceFileCount, List<String> targetLocales,
            int totalTranslations, int upToDate, int stale, int missing,
            List<DocTranslationStatus> details) {}
}
