package com.auraboot.framework.bi.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Update request for a first-class low-code report ({@code ab_report}, Phase 4 slice 2a).
 *
 * <p>Now backs an idempotent UPSERT (Phase 4 slice 2b-1): a {@code PUT /{pid}} to a not-yet
 * existing pid CREATES the row (REST-idempotent), so the frontend dual-write can sync an
 * {@code ab_report} shadow keyed by the page pid without first checking existence. {@code code}
 * is therefore accepted here but is consumed ONLY on the create branch (it is tenant-unique and
 * {@code NOT NULL}); it is immutable and ignored when the row already exists. {@code tenantId} /
 * {@code pid} remain absent ({@code pid} is the path variable; tenant comes from MetaContext).
 * {@code title} / {@code profile} / {@code status} are optional patches; {@code dsl} is the report
 * JSON object that always round-trips.
 */
@Data
public class ReportDefinitionUpdateRequest {

    /**
     * Tenant-unique business code; required only when the PUT creates a missing row (it maps to
     * {@code ab_report.code}, {@code uk_ab_report_tenant_code}). Ignored on update — {@code code}
     * is immutable once the row exists.
     */
    private String code;

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
