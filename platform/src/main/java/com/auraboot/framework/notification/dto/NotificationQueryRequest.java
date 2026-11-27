package com.auraboot.framework.notification.dto;

import lombok.Data;

/**
 * Request DTO for querying notifications.
 *
 * @since 5.1.0
 */
@Data
public class NotificationQueryRequest {

    private String category;     // SYSTEM / APPROVAL / ALERT / BUSINESS
    private Boolean isRead;
    @jakarta.validation.constraints.Pattern(regexp = "pages/[a-z0-9-]+(?:/[a-z0-9-]+)+")
    @jakarta.validation.constraints.Size(max = 120)
    private String miniProgramPage;
    private int pageNum = 1;
    private int pageSize = 20;
}
