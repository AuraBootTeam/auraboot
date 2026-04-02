package com.auraboot.framework.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * DTO for knowledge base responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseDTO {
    private String pid;
    private String name;
    private String description;
    private String status;
    private String embeddingProvider;
    private String embeddingModel;
    private Integer embeddingDimension;
    private String chunkStrategy;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Integer docCount;
    private Integer chunkCount;
    private Instant createdAt;
}
