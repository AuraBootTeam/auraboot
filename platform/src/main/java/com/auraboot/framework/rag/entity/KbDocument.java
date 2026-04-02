package com.auraboot.framework.rag.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * KB document entity — an uploaded file or entity content indexed into a knowledge base.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ab_kb_document")
public class KbDocument {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("pid")
    private String pid;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("kb_id")
    private String kbId;

    @TableField("file_pid")
    private String filePid;

    @TableField("doc_name")
    private String docName;

    @TableField("doc_type")
    private String docType;

    @TableField("file_size")
    private Long fileSize;

    @TableField("char_count")
    private Integer charCount;

    @TableField("chunk_count")
    private Integer chunkCount;

    @TableField("source_type")
    private String sourceType;

    @TableField("source_entity_id")
    private String sourceEntityId;

    @TableField("content_hash")
    private String contentHash;

    @TableField("status")
    private String status;

    @TableField("error_message")
    private String errorMessage;

    @TableField("process_started_at")
    private Instant processStartedAt;

    @TableField("process_completed_at")
    private Instant processCompletedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField("created_by")
    private Long createdBy;

    @TableLogic(value = "false", delval = "true")
    @TableField("deleted_flag")
    private Boolean deletedFlag;
}
