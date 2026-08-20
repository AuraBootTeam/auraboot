package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

/**
 * Lightweight DTO for mobile schema sync.
 * Returns only version metadata (no blocks) so clients can decide
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

    @JsonProperty("kind")
    private String kind;

    @JsonProperty("modelCode")
    private String modelCode;

    @JsonProperty("runtime")
    private PageSchemaRuntimeDTO runtime;

    @JsonIgnore
    private String pagePid;

    @JsonIgnore
    private String runtimeReleasePid;

    @JsonIgnore
    private Long runtimeChannelVersion;

    @JsonIgnore
    private Long runtimeSourceVersion;

    @JsonIgnore
    private String runtimeSnapshotChecksum;
}
