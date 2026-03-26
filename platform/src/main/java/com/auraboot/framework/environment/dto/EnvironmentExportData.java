package com.auraboot.framework.environment.dto;

import lombok.Data;

import java.util.Date;
import java.util.Map;

/**
 * DTO representing exported environment configuration, used for import/export between environments.
 */
@Data
public class EnvironmentExportData {

    private String code;
    private String name;
    private String description;
    private String apiBaseUrl;
    private Map<String, Object> dbConnectionInfo;
    private Boolean isDefault;
    private Integer sortOrder;
    private Date exportedAt;
}
