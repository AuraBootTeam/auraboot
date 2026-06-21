package com.auraboot.framework.bi.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Update request for a first-class low-code report ({@code ab_report}, Phase 4 slice 2a).
 *
 * <p>PURELY ADDITIVE (see {@link ReportDefinitionCreateRequest}). {@code code} / {@code tenantId}
 * / {@code pid} are immutable on update and are intentionally absent; {@code title} / {@code profile}
 * / {@code status} are optional patches, {@code dsl} is the report JSON object that always round-trips.
 */
@Data
public class ReportDefinitionUpdateRequest {

    /** New title; optional — left unchanged when null. */
    private String title;

    /** New render/layout profile; optional — left unchanged when null. */
    private String profile;

    /** New status (e.g. draft/published); optional — left unchanged when null. */
    private String status;

    /** The whole ReportDsl as a JSON object (replaces the stored blob). */
    @NotNull
    private JsonNode dsl;
}
