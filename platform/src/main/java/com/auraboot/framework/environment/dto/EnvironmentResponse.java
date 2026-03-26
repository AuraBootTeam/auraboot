package com.auraboot.framework.environment.dto;

import lombok.Data;

import java.util.Date;
import java.util.Map;

/**
 * Response DTO for environment data.
 */
@Data
public class EnvironmentResponse {

    private String pid;
    private String code;
    private String name;
    private String description;
    private String apiBaseUrl;
    private Map<String, Object> dbConnectionInfo;
    private String status;
    private Boolean isDefault;
    private Integer sortOrder;
    private Date createdAt;
    private Date updatedAt;
}
