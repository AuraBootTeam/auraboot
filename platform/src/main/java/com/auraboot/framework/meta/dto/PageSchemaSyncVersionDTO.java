package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

/**
 * Lightweight DTO for mobile schema sync.
 * Returns only version metadata (no dslSchema) so clients can decide
 * which schemas need re-fetching.
 */
@Data
public class PageSchemaSyncVersionDTO {

    @JsonProperty("pageKey")
    private String pageKey;

    @JsonProperty("schemaVersion")
    private Integer schemaVersion;

    @JsonProperty("updatedAt")
    private Instant updatedAt;

    @JsonProperty("pageType")
    private String pageType;

    @JsonProperty("modelCode")
    private String modelCode;
}
