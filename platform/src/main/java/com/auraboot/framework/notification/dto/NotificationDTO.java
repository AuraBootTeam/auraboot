package com.auraboot.framework.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Notification data transfer object for API responses.
 *
 * @since 5.1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDTO {

    private Long id;
    private String title;
    private String content;
    private String category;
    private String priority;
    private String sourceType;
    private String sourceId;
    private Boolean isRead;
    private Instant readAt;
    private Instant createdAt;
}
