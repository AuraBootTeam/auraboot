package com.auraboot.framework.notification.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for creating/updating a notification template.
 *
 * @since 5.1.0
 */
@Data
public class NotificationTemplateCreateRequest {

    @NotBlank
    private String code;

    @NotBlank
    private String name;

    /**
     * Channel: IN_APP / EMAIL / SMS.
     */
    @NotBlank
    private String channel;

    private String subjectTemplate;

    @NotBlank
    private String bodyTemplate;

    /**
     * Variable definitions as JSON string.
     */
    private String variables;

    private boolean enabled = true;
}
