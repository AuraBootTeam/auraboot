package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * DTO for async export task status.
 */
@Data
public class ExportTaskDTO {

    private String pid;
    private String queryCode;
    private String status;
    private Integer progress;
    private Long totalRows;
    private Long processedRows;
    private Long fileSize;
    private String format;
    private String errorMessage;
    private String downloadUrl;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private LocalDateTime expiresAt;
}
