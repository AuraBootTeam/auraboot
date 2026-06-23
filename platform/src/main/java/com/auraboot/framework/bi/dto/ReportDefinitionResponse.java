package com.auraboot.framework.bi.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.Instant;

/**
 * Full report-definition response ({@code ab_report}, Phase 4 slice 2a).
 *
 * <p>PURELY ADDITIVE transport shape returned by {@code GET/POST/PUT /api/report-definitions[/{pid}]}.
 * The internal {@code id} and {@code tenantId} are intentionally NOT exposed; the stable external
 * id is {@code pid}. {@code dsl} is the report JSON returned as a {@link JsonNode} object (so the
 * client receives the ReportDsl as an object, not an escaped string).
 */
@Data
public class ReportDefinitionResponse {

    /** Stable external report id (ULID). */
    private String pid;

    /** Tenant-unique business code. */
    private String code;

    private String title;

    private String profile;

    /** Status (e.g. draft/published). */
    private String status;

    private Integer version;

    /** The whole ReportDsl as a JSON object (parsed from the entity's jsonb String column). */
    private JsonNode dsl;

    private Instant createdAt;

    private Instant updatedAt;
}
