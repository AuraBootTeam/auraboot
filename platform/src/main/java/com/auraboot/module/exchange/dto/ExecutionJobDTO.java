package com.auraboot.module.exchange.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/** Owner-scoped logical projection over import, export, and document execution jobs. */
@Data
@Builder
public class ExecutionJobDTO {

    private String pid;
    private String type;
    private String definitionCode;
    private String status;
    private Integer progress;
    private Long totalUnits;
    private Long processedUnits;
    private Long succeededUnits;
    private Long failedUnits;
    private String format;
    /** Human-readable outcome summary for generic DSL tables. */
    private String summary;
    /** Public, re-authorized artifact endpoint; null when unavailable or expired. */
    private String downloadUrl;
    private Artifact artifact;
    private List<Artifact> artifacts;
    private Instant createdAt;
    private Instant completedAt;

    @Data
    @Builder
    public static class Artifact {
        private String kind;
        private String format;
        private String filename;
        private String downloadUrl;
        private Long size;
        private String checksumSha256;
        private Instant expiresAt;
        private boolean available;
    }
}
