package com.auraboot.framework.workbench.dto;

import lombok.Data;

import java.time.Instant;

/**
 * DTO for announcement responses.
 *
 * @since 6.5.0
 */
@Data
public class AnnouncementDTO {

    private Long id;
    private String title;
    private String content;
    private String priority;
    private String status;
    private Boolean pinned;
    private Long publishedBy;
    private String publishedByName;
    private Instant publishedAt;
    private Instant expiresAt;
    private Instant createdAt;
}
