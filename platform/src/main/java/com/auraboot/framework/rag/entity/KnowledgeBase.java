package com.auraboot.framework.rag.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Knowledge base entity — top-level container for document collections.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ab_knowledge_base")
public class KnowledgeBase {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("status")
    private String status;

    @TableField("embedding_provider")
    private String embeddingProvider;

    @TableField("embedding_model")
    private String embeddingModel;

    @TableField("embedding_dimension")
    private Integer embeddingDimension;

    @TableField("chunk_strategy")
    private String chunkStrategy;

    @TableField("chunk_size")
    private Integer chunkSize;

    @TableField("chunk_overlap")
    private Integer chunkOverlap;

    @TableField("doc_count")
    private Integer docCount;

    @TableField("chunk_count")
    private Integer chunkCount;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField("created_by")
    private Long createdBy;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableField("updated_by")
    private Long updatedBy;

    @TableLogic(value = "false", delval = "true")
    @TableField("deleted_flag")
    private Boolean deletedFlag;
}
