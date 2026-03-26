package com.auraboot.framework.notification.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request DTO for sending a notification via template.
 *
 * @since 5.1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationSendRequest {

    @NotBlank
    private String templateCode;

    @NotBlank
    private String recipientId;

    private Map<String, Object> variables;

    private String sourceType;
    private String sourceId;
}
