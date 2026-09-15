package com.auraboot.framework.bi.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Create request for the canonical report-definition store.
 * Excludes persistence identities, tenant context and audit fields from client input.
 * The report DSL is stored as JSON; optional analysis provenance is verified by the server.
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

    /** Optional successful analysis owned by the current user; verified against the saved query. */
    private String sourceAnalysisId;

    /** The complete ReportDsl as a JSON object. */
    @NotNull
    private JsonNode dsl;
}
