package com.auraboot.framework.bi.dto;

import lombok.Data;

import java.time.Instant;

/**
 * Lightweight list-row for report definitions ({@code ab_report}, Phase 4 slice 2a).
 *
 * <p>PURELY ADDITIVE. Deliberately OMITS the (potentially large) {@code dsl} blob: the list
 * endpoint returns only pid/code/title/status/version/updatedAt so a directory view never has to
 * deserialize every report's full DSL. Fetch the full {@link ReportDefinitionResponse} via
 * {@code GET /api/report-definitions/{pid}} when the DSL is needed.
 */
@Data
public class ReportDefinitionSummary {

    /** Stable external report id (ULID). */
    private String pid;

    /** Tenant-unique business code. */
    private String code;

    private String title;

    /** Status (e.g. draft/published). */
    private String status;

    private Integer version;

    private Instant updatedAt;
}
