package com.auraboot.framework.cloudconfig.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request DTO for creating or updating a cloud configuration.
 *
 * @since 6.3.0
 */
@Data
public class CloudConfigSaveRequest {

    /** PLATFORM | TENANT */
    @NotBlank(message = "configLevel is required")
    private String configLevel;

    /** SMS | EMAIL | OAUTH | STORAGE | CDN */
    @NotBlank(message = "serviceType is required")
    private String serviceType;

    /** Provider identifier: tencent_sms, aliyun_sms, google, apple, wechat_web, smtp, etc. */
    @NotBlank(message = "providerCode is required")
    private String providerCode;

    /** JSON configuration string (sensitive fields will be auto-encrypted) */
    @NotBlank(message = "config is required")
    private String config;

    @NotNull(message = "enabled is required")
    private Boolean enabled;

    /** Lower value = higher priority. Default 0. */
    private Integer priority;

    /** Optional: PID for update. If null, creates a new config. */
    private String pid;
}
