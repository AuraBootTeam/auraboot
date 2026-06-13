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

    /**
     * Kind of {@link #recipientId}: {@code "user"} (default — a user id or email),
     * {@code "role"} (a role code — fans out to every member with that role) or
     * {@code "group"}/{@code "team"} (a team pid — fans out to every team member).
     * Null/blank is treated as {@code "user"} for backward compatibility.
     */
    private String recipientType;

    private Map<String, Object> variables;

    private String sourceType;
    private String sourceId;
}
