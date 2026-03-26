package com.auraboot.framework.rag.service;

import com.auraboot.framework.rag.service.ChunkingService.ChunkResult;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ChunkingService — paragraph-aware text splitting.
 * Pure logic tests, no Spring context needed.
 */
class ChunkingServiceTest {

    private final ChunkingService chunkingService = new ChunkingService();

    // =========================================================================
    // Basic chunking
    // =========================================================================

    @Test
    @DisplayName("CHUNK-U01: Null/blank text returns empty list")
    void chunk_nullText() {
        assertThat(chunkingService.chunk(null, 500, 50)).isEmpty();
        assertThat(chunkingService.chunk("", 500, 50)).isEmpty();
        assertThat(chunkingService.chunk("   ", 500, 50)).isEmpty();
    }

    @Test
    @DisplayName("CHUNK-U02: Short text returns single chunk")
    void chunk_shortText() {
        String text = "This is a short document.";
        List<ChunkResult> chunks = chunkingService.chunk(text, 500, 50);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo(text);
        assertThat(chunks.get(0).index()).isZero();
        assertThat(chunks.get(0).charCount()).isEqualTo(text.length());
        assertThat(chunks.get(0).tokenCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("CHUNK-U03: Paragraph-aware splitting respects paragraph boundaries")
    void chunk_paragraphAware() {
        String text = "First paragraph content here.\n\nSecond paragraph with more text.\n\nThird paragraph concludes.";
        List<ChunkResult> chunks = chunkingService.chunk(text, 40, 0);

        // Each paragraph < 40 chars, but combined they exceed it
        assertThat(chunks).hasSizeGreaterThan(1);
        // Each chunk should contain complete paragraphs (not mid-sentence cuts)
        for (ChunkResult chunk : chunks) {
            assertThat(chunk.content()).doesNotStartWith("\n");
            assertThat(chunk.content()).isNotBlank();
        }
    }

    @Test
    @DisplayName("CHUNK-U04: Overlap creates shared content between consecutive chunks")
    void chunk_withOverlap() {
        // Create text with distinct paragraphs
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            sb.append("Paragraph number ").append(i).append(" with some content.\n\n");
        }
        String text = sb.toString();

        List<ChunkResult> noOverlap = chunkingService.chunk(text, 100, 0);
        List<ChunkResult> withOverlap = chunkingService.chunk(text, 100, 30);

        // With overlap, we expect more chunks (due to repeated content)
        // or at minimum, consecutive chunks share some text
        assertThat(withOverlap.size()).isGreaterThanOrEqualTo(noOverlap.size());
    }

    @Test
    @DisplayName("CHUNK-U05: Large paragraph exceeding chunkSize is split further")
    void chunk_largeParagraph() {
        // Create a single paragraph longer than chunkSize
        String longParagraph = "A".repeat(100) + ". " + "B".repeat(100) + ". " + "C".repeat(100) + ".";

        List<ChunkResult> chunks = chunkingService.chunk(longParagraph, 120, 10);

        assertThat(chunks).hasSizeGreaterThan(1);
        // Each chunk should be at most roughly chunkSize + some margin
        for (ChunkResult chunk : chunks) {
            assertThat(chunk.charCount()).isLessThanOrEqualTo(150); // some tolerance
        }
    }

    @Test
    @DisplayName("CHUNK-U06: Chunk indices are sequential starting from 0")
    void chunk_sequentialIndices() {
        String text = "Para 1.\n\nPara 2.\n\nPara 3.\n\nPara 4.\n\nPara 5.";
        List<ChunkResult> chunks = chunkingService.chunk(text, 15, 0);

        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).index()).isEqualTo(i);
        }
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    @DisplayName("CHUNK-U07: chunkSize <= 0 defaults to 500")
    void chunk_invalidChunkSize() {
        String text = "Small text.";
        List<ChunkResult> chunks = chunkingService.chunk(text, 0, 0);

        // With default 500 chars, small text should be 1 chunk
        assertThat(chunks).hasSize(1);
    }

    @Test
    @DisplayName("CHUNK-U08: overlap >= chunkSize is capped to chunkSize/5")
    void chunk_excessiveOverlap() {
        String text = "Para 1 text here.\n\nPara 2 text here.\n\nPara 3 text here.";
        // overlap (1000) > chunkSize (30) should not cause infinite loop
        List<ChunkResult> chunks = chunkingService.chunk(text, 30, 1000);

        assertThat(chunks).isNotEmpty();
    }

    @Test
    @DisplayName("CHUNK-U09: CJK text token estimation")
    void chunk_cjkTokenEstimation() {
        String cjkText = "这是一段中文测试文本用于验证分块服务的标记估算功能";
        List<ChunkResult> chunks = chunkingService.chunk(cjkText, 500, 0);

        assertThat(chunks).hasSize(1);
        // CJK chars count as ~1 token each
        assertThat(chunks.get(0).tokenCount()).isGreaterThan(10);
    }

    @Test
    @DisplayName("CHUNK-U10: Sentence-boundary detection at period, question mark, exclamation")
    void chunk_sentenceBoundary() {
        // Single paragraph with multiple sentences, should split at sentence boundary
        String text = "First sentence here. Second sentence here? Third sentence here! Fourth sentence continues.";
        List<ChunkResult> chunks = chunkingService.chunk(text, 50, 0);

        assertThat(chunks).hasSizeGreaterThan(1);
        // Check that chunks end at sentence boundaries where possible
        for (ChunkResult chunk : chunks) {
            String content = chunk.content().trim();
            if (chunks.indexOf(chunk) < chunks.size() - 1) {
                // Non-last chunks should ideally end with sentence-ending punctuation
                char lastChar = content.charAt(content.length() - 1);
                assertThat(lastChar)
                        .as("Chunk should end at sentence boundary: '%s'", content)
                        .isIn('.', '!', '?', '。', '！', '？', ' ');
            }
        }
    }

    @Test
    @DisplayName("CHUNK-U11: Mixed CJK and English text")
    void chunk_mixedLanguage() {
        String text = "AuraBoot 是一个低代码平台。\n\nIt supports multiple languages.\n\n支持中英文混合文档。";
        List<ChunkResult> chunks = chunkingService.chunk(text, 50, 0);

        assertThat(chunks).isNotEmpty();
        // Verify all content is preserved across chunks
        String combined = chunks.stream()
                .map(ChunkResult::content)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
        assertThat(combined).contains("AuraBoot");
        assertThat(combined).contains("multiple languages");
        assertThat(combined).contains("中英文混合");
    }

    @Test
    @DisplayName("CHUNK-U12: Multiple consecutive empty lines treated as single separator")
    void chunk_multipleEmptyLines() {
        String text = "Para 1.\n\n\n\n\nPara 2.\n\n\n\nPara 3.";
        List<ChunkResult> chunks = chunkingService.chunk(text, 500, 0);

        assertThat(chunks).isNotEmpty();
        // Empty paragraphs should be skipped
        for (ChunkResult chunk : chunks) {
            assertThat(chunk.content()).isNotBlank();
        }
    }
}
