package com.auraboot.framework.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for creating/updating a webhook subscription.
 *
 * @since 5.1.0
 */
@Data
public class WebhookCreateRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String targetUrl;

    @NotBlank
    private String eventType;

    private String modelCode;
    private String filterExpression;
    private String secret;
    private String headers;
    private Integer maxRetries = 3;
    private Integer timeoutMs = 10000;
    private boolean enabled = true;
}
