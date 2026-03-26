package com.auraboot.framework.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Recipient information for batch notification sending.
 *
 * @since 5.1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRecipient {

    private Long userId;
    private String email;
    private String mobile;
}
