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
    private String sourceType;
    private String status;
    private String errorMessage;
    private Instant processStartedAt;
    private Instant processCompletedAt;
    private Instant createdAt;
}
