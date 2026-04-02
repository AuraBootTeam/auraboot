package com.auraboot.framework.rag.service;

import com.auraboot.framework.rag.util.VectorUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits text into chunks for embedding.
 * Uses fixed-size chunking with overlap and paragraph-aware boundaries.
 */
@Slf4j
@Service
public class ChunkingService {

    /**
     * Split text into chunks.
     *
     * @param text       the full document text
     * @param chunkSize  target characters per chunk
     * @param overlap    overlap characters between consecutive chunks
     * @return list of text chunks
     */
    public List<ChunkResult> chunk(String text, int chunkSize, int overlap) {
        if (text == null || text.isBlank()) return List.of();
        if (chunkSize <= 0) chunkSize = 500;
        if (overlap < 0) overlap = 0;
        if (overlap >= chunkSize) overlap = chunkSize / 5;

        // Split by paragraphs first for paragraph-aware chunking
        String[] paragraphs = text.split("\\n\\s*\\n");
        List<ChunkResult> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int chunkIndex = 0;

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;

            // If adding this paragraph exceeds chunk size, flush current
            if (current.length() > 0 && current.length() + trimmed.length() + 1 > chunkSize) {
                chunks.add(buildChunk(current.toString(), chunkIndex++));

                // Apply overlap: keep the tail of the current chunk
                if (overlap > 0 && current.length() > overlap) {
                    String tail = current.substring(current.length() - overlap);
                    current.setLength(0);
                    current.append(tail);
                } else {
                    current.setLength(0);
                }
            }

            if (current.length() > 0) current.append("\n\n");

            // If a single paragraph exceeds chunk size, split it by sentences/hard boundaries
            if (trimmed.length() > chunkSize) {
                // Flush anything accumulated
                if (current.length() > 0) {
                    chunks.add(buildChunk(current.toString(), chunkIndex++));
                    current.setLength(0);
                }
                // Split large paragraph
                for (ChunkResult sub : splitLargeParagraph(trimmed, chunkSize, overlap, chunkIndex)) {
                    chunks.add(sub);
                    chunkIndex++;
                }
            } else {
                current.append(trimmed);
            }
        }

        // Flush remaining
        if (current.length() > 0) {
            chunks.add(buildChunk(current.toString(), chunkIndex));
        }

        return chunks;
    }

    private List<ChunkResult> splitLargeParagraph(String text, int chunkSize, int overlap, int startIndex) {
        List<ChunkResult> results = new ArrayList<>();
        int idx = startIndex;
        int pos = 0;

        while (pos < text.length()) {
            int end = Math.min(pos + chunkSize, text.length());

            // Try to break at a sentence boundary
            if (end < text.length()) {
                int sentenceBreak = findSentenceBreak(text, pos + chunkSize / 2, end);
                if (sentenceBreak > pos) {
                    end = sentenceBreak;
                }
            }

            results.add(buildChunk(text.substring(pos, end).trim(), idx++));

            // If we've reached the end, stop
            if (end >= text.length()) break;

            // Advance with overlap
            int nextPos = end - overlap;
            if (nextPos <= pos || nextPos >= end) {
                nextPos = end; // Safety: avoid infinite loop
            }
            pos = nextPos;
        }
        return results;
    }

    /**
     * Find a sentence-ending position (. ! ? 。！？) within [start, end].
     */
    private int findSentenceBreak(String text, int start, int end) {
        for (int i = end; i >= start; i--) {
            char c = text.charAt(i - 1);
            if (c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？') {
                return i;
            }
        }
        // Fallback: try to break at a space
        for (int i = end; i >= start; i--) {
            if (Character.isWhitespace(text.charAt(i - 1))) {
                return i;
            }
        }
        return end; // No good break found
    }

    private ChunkResult buildChunk(String content, int index) {
        return new ChunkResult(index, content, content.length(), VectorUtils.estimateTokens(content));
    }

    public record ChunkResult(int index, String content, int charCount, int tokenCount) {}
}
