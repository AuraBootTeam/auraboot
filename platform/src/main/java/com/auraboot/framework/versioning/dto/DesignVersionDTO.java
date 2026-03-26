package com.auraboot.framework.versioning.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Version history entry DTO.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DesignVersionDTO {

    private String pid;
    private String resourceType;
    private String resourceId;
    private String version;
    private String operation;
    private String operationBy;
    private Instant operationAt;
    private String description;
    private String parentVersionId;

    /**
     * Full snapshot is only included when explicitly requested
     * (e.g. for rollback or compare). List queries omit this.
     */
    private JsonNode schemaSnapshot;
}
