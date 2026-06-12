package com.auraboot.framework.bi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * Request payload for Report Designer artifact export.
 */
@Data
public class ReportExportRequest {

    @NotBlank(message = "reportPid is required")
    @JsonProperty("reportPid")
    private String reportPid;

    @JsonProperty("parameters")
    private Map<String, Object> parameters;
}
