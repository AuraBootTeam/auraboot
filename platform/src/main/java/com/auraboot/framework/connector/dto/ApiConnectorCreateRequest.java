package com.auraboot.framework.connector.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for creating/updating an API connector.
 *
 * @since 5.1.0
 */
@Data
public class ApiConnectorCreateRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String baseUrl;

    private String authType = "none";
    private String authConfig;
    private String defaultHeaders;
    private Integer timeoutMs = 10000;
    private String retryPolicy;
    private boolean enabled = true;
}
