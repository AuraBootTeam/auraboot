package com.auraboot.framework.rag.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * KB chunk entity — text fragment with embedding vector for similarity search.
 * <p>
 * Note: the {@code embedding} column (vector(1536)) is NOT mapped here because
 * MyBatis-Plus has no native pgvector type handler. Vector operations use JdbcTemplate directly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ab_kb_chunk")
public class KbChunk {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("kb_id")
    private String kbId;

    @TableField("doc_id")
    private String docId;

    @TableField("chunk_index")
    private Integer chunkIndex;

    @TableField("content")
    private String content;

    @TableField("char_count")
    private Integer charCount;

    @TableField("token_count")
    private Integer tokenCount;

    @TableField("metadata")
    private String metadata;

    @TableField("embedding_status")
    private String embeddingStatus;

    // embedding vector(1536) — handled via JdbcTemplate, not MyBatis-Plus

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
