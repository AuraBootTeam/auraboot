package com.auraboot.framework.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for updating a notification preference.
 *
 * @since 6.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreferenceUpdateRequest {

    @NotBlank
    private String channel;

    @NotBlank
    private String category;

    @NotNull
    private Boolean enabled;
}
