package com.auraboot.framework.workbench.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.Instant;

/**
 * DTO for announcement responses.
 *
 * @since 6.5.0
 */
@Data
public class AnnouncementDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private String title;
    private String content;
    private String priority;
    private String status;
    private Boolean pinned;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long publishedBy;
    private String publishedByName;
    private Instant publishedAt;
    private Instant expiresAt;
    private Instant createdAt;
}
