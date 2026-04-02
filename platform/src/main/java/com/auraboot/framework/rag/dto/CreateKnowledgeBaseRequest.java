package com.auraboot.framework.rag.dto;

import lombok.Data;

/**
 * Request DTO for creating a knowledge base.
 */
@Data
public class CreateKnowledgeBaseRequest {
    private String name;
    private String description;
    private String embeddingProvider;
    private String embeddingModel;
    private Integer embeddingDimension;
    private Integer chunkSize;
    private Integer chunkOverlap;
}
