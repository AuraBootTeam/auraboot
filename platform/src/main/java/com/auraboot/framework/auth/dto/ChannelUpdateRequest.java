package com.auraboot.framework.auth.dto;

import lombok.Data;

/**
 * Request DTO for updating a tenant login channel's enabled state and sort order.
 *
 * @since 7.0.0
 */
@Data
public class ChannelUpdateRequest {

    /** Channel code: EMAIL_PASSWORD | SMS | EMAIL_CODE | WECHAT | GOOGLE | APPLE */
    private String channel;

    /** Whether this channel is enabled */
    private Boolean enabled;

    /** Display sort order (lower = higher priority) */
    private Integer sortOrder;
}
