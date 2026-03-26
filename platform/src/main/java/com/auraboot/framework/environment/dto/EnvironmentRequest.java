package com.auraboot.framework.environment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * Request DTO for creating/updating an environment.
 */
@Data
public class EnvironmentRequest {

    @NotBlank(message = "Environment code is required")
    @Size(max = 50, message = "Code must be at most 50 characters")
    @Pattern(regexp = "^[a-z][a-z0-9_-]*$", message = "Code must start with lowercase letter and contain only lowercase letters, digits, hyphens and underscores")
    private String code;

    @NotBlank(message = "Environment name is required")
    @Size(max = 100, message = "Name must be at most 100 characters")
    private String name;

    @Size(max = 2000, message = "Description must be at most 2000 characters")
    private String description;

    @Size(max = 500, message = "API base URL must be at most 500 characters")
    private String apiBaseUrl;

    private Map<String, Object> dbConnectionInfo;

    private Boolean isDefault;

    private Integer sortOrder;
}
