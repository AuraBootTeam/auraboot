package com.auraboot.framework.workbench.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.Instant;

/**
 * Request DTO for creating/updating announcements.
 *
 * @since 6.5.0
 */
@Data
public class AnnouncementRequest {

    @NotBlank(message = "Title is required")
    private String title;

    private String content;

    /** normal | high | urgent */
    private String priority;

    /** draft | active */
    private String status;

    private Boolean pinned;

    private Instant expiresAt;
}
