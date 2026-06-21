package com.auraboot.framework.bi.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Create request for a first-class low-code report ({@code ab_report}, Phase 4 slice 2a).
 *
 * <p>PURELY ADDITIVE: this DTO backs the additive {@code /api/report-definitions} CRUD API
 * (slice 2a). The live report designer still persists via {@code ab_page_schema} +
 * {@code extension.reportDsl}; nothing in the existing UI calls this endpoint yet (slice 2b).
 *
 * <p>The DTO is a thin transport shape and intentionally does NOT expose the
 * {@code ReportEntity} (no {@code id}, {@code tenantId}, audit columns, or soft-delete flag).
 * {@code dsl} is the report JSON carried as a {@link JsonNode} so the client round-trips the
 * ReportDsl object as-is; the controller serializes it to the entity's String/jsonb column.
 */
@Data
public class ReportDefinitionCreateRequest {

    /** Tenant-unique business code (maps to {@code ab_report.code}, uk_ab_report_tenant_code). */
    @NotBlank
    private String code;

    /** Human-readable report title. */
    @NotBlank
    private String title;

    /** Render/layout profile; optional — defaults to {@code paged-media} when blank. */
    private String profile;

    /** The whole ReportDsl as a JSON object (1:1 with {@code extension.reportDsl}). */
    @NotNull
    private JsonNode dsl;
}
