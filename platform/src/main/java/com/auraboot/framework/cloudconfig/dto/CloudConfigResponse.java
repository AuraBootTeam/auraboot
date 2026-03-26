package com.auraboot.framework.cloudconfig.dto;

import lombok.Data;

import java.time.Instant;

/**
 * Response DTO for cloud configuration (with sensitive fields masked).
 *
 * @since 6.3.0
 */
@Data
public class CloudConfigResponse {

    private String pid;
    private String configLevel;
    private Long tenantId;
    private String serviceType;
    private String providerCode;

    /** JSON config with sensitive fields masked (e.g., "****cret") */
    private String config;

    private Boolean enabled;
    private Integer priority;
    private Instant createdAt;
    private Instant updatedAt;
}
