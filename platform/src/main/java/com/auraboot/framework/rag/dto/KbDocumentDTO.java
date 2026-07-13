package com.auraboot.framework.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * DTO for KB document responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KbDocumentDTO {
    private String pid;
    private String kbId;
    private String docName;
    private String docType;
    private Long fileSize;
    private Integer charCount;
    private Integer chunkCount;

    /**
     * How many of this document's chunks actually carry a vector.
     *
     * <p>A document goes "completed" when its text is chunked and stored. Embedding is a
     * separate remote step that can fail on every chunk while the document still reports
     * green — leaving a knowledge base that looks perfect and answers nothing, because
     * retrieval quietly falls back to keyword matching. Zero here, with a non-zero
     * chunkCount, is exactly that state.
     */
    private Integer embeddedChunkCount;
    private String sourceType;
    private String status;
    private String errorMessage;
    private Instant processStartedAt;
    private Instant processCompletedAt;
    private Instant createdAt;
}
