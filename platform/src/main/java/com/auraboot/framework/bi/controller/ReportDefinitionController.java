package com.auraboot.framework.bi.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bi.dao.entity.ReportEntity;
import com.auraboot.framework.bi.dto.ReportDefinitionCreateRequest;
import com.auraboot.framework.bi.dto.ReportDefinitionResponse;
import com.auraboot.framework.bi.dto.ReportDefinitionSummary;
import com.auraboot.framework.bi.dto.ReportDefinitionUpdateRequest;
import com.auraboot.framework.bi.service.ReportStorageService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Additive CRUD API for first-class low-code report definitions ({@code ab_report}, Phase 4 slice 2a).
 *
 * <p>PURELY ADDITIVE: the live report designer still persists via {@code ab_page_schema} +
 * {@code extension.reportDsl}; NOTHING in the existing UI calls these endpoints yet — wiring the
 * frontend is slice 2b. This controller only exposes the slice-1 {@link ReportStorageService}
 * spine. Base path is {@code /api/report-definitions} (deliberately distinct from the already-taken
 * {@code /api/reports}, owned by {@code ReportExportController} + {@code PivotQueryController}) so
 * there is no Spring ambiguous-mapping at startup.
 *
 * <p>All endpoints are tenant-scoped via the existing tenant interceptor ({@link MetaContext}) and
 * every one is guarded with {@link RequirePermission}. (The sibling {@code ReportScheduleController}
 * in this package ships with ZERO guards — a known gap; this controller deliberately does not
 * replicate that.)
 *
 * <p>STOPGAP permissions — reuses the existing template-aliased report codes
 * ({@code REPORT_READ}/{@code REPORT_MANAGE}). B6 will introduce a clean
 * report:view/edit/export/schedule/publish family (see B1 discovery Q11) and these annotations
 * should migrate to it.
 *
 * <p>{@code dsl} is carried as a JSON object ({@link JsonNode}) over the wire and round-trips to the
 * entity's String/jsonb column via the package {@link ObjectMapper} (object in -&gt; object out).
 */
@Slf4j
@RestController
@RequestMapping("/api/report-definitions")
@RequiredArgsConstructor
@Tag(name = "Report Definitions", description = "Additive CRUD for first-class low-code report definitions (ab_report)")
public class ReportDefinitionController {

    private final ReportStorageService reportStorageService;
    private final ObjectMapper objectMapper;

    @PostMapping
    @Operation(summary = "Create a report definition", description = "Persists a new ab_report row and returns the minted pid")
    @RequirePermission(MetaPermission.REPORT_MANAGE)
    public ApiResponse<ReportDefinitionResponse> create(@Valid @RequestBody ReportDefinitionCreateRequest request) {
        ReportEntity entity = new ReportEntity();
        entity.setTenantId(MetaContext.getCurrentTenantId());
        entity.setCode(request.getCode());
        entity.setTitle(request.getTitle());
        entity.setProfile(request.getProfile());
        entity.setDsl(writeDsl(request.getDsl()));
        entity.setCreatedBy(MetaContext.getCurrentUserId());
        entity.setUpdatedBy(MetaContext.getCurrentUserId());
        ReportEntity created = reportStorageService.create(entity);
        return ApiResponse.success(toResponse(created));
    }

    @PutMapping("/{pid}")
    @Operation(summary = "Update a report definition", description = "Updates title/profile/status/dsl of an existing ab_report row")
    @RequirePermission(MetaPermission.REPORT_MANAGE)
    public ApiResponse<ReportDefinitionResponse> update(@PathVariable String pid,
                                                        @Valid @RequestBody ReportDefinitionUpdateRequest request) {
        ReportEntity existing = requireOwned(pid);
        if (request.getTitle() != null) {
            existing.setTitle(request.getTitle());
        }
        if (request.getProfile() != null) {
            existing.setProfile(request.getProfile());
        }
        if (request.getStatus() != null) {
            existing.setStatus(request.getStatus());
        }
        existing.setDsl(writeDsl(request.getDsl()));
        existing.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
        existing.setUpdatedBy(MetaContext.getCurrentUserId());
        boolean updated = reportStorageService.update(existing);
        if (!updated) {
            // Lost-update race: the row was soft-deleted between requireOwned and update.
            throw new BusinessException(ResponseCode.NOT_FOUND, "Report not found: " + pid);
        }
        return ApiResponse.success(toResponse(reportStorageService.findByPid(pid)));
    }

    @GetMapping("/{pid}")
    @Operation(summary = "Get a report definition", description = "Loads one live ab_report row (404 if not found / soft-deleted)")
    @RequirePermission(MetaPermission.REPORT_READ)
    public ApiResponse<ReportDefinitionResponse> get(@PathVariable String pid) {
        return ApiResponse.success(toResponse(requireOwned(pid)));
    }

    @GetMapping
    @Operation(summary = "List report definitions", description = "Lists the current tenant's live reports (lightweight: no dsl)")
    @RequirePermission(MetaPermission.REPORT_READ)
    public ApiResponse<List<ReportDefinitionSummary>> list() {
        List<ReportDefinitionSummary> rows = reportStorageService
                .listByTenant(MetaContext.getCurrentTenantId())
                .stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
        return ApiResponse.success(rows);
    }

    @DeleteMapping("/{pid}")
    @Operation(summary = "Soft-delete a report definition", description = "Soft-deletes one live ab_report row (404 if not found)")
    @RequirePermission(MetaPermission.REPORT_MANAGE)
    public ApiResponse<Void> delete(@PathVariable String pid) {
        requireOwned(pid);
        reportStorageService.softDelete(pid);
        return ApiResponse.success();
    }

    /**
     * Load a live report by pid and assert it belongs to the current tenant, else 404. This both
     * surfaces a clean not-found and prevents a cross-tenant read by pid (the storage finder is
     * pid-keyed and not tenant-scoped on its own).
     */
    private ReportEntity requireOwned(String pid) {
        ReportEntity entity = reportStorageService.findByPid(pid);
        if (entity == null || !MetaContext.getCurrentTenantId().equals(entity.getTenantId())) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Report not found: " + pid);
        }
        return entity;
    }

    private String writeDsl(JsonNode dsl) {
        try {
            return objectMapper.writeValueAsString(dsl);
        } catch (JsonProcessingException e) {
            // The dsl arrived as a parsed JsonNode, so re-serialization should never fail; surface
            // as a validation error rather than swallowing.
            throw new BusinessException(ResponseCode.BadParam, "Invalid report dsl JSON", e);
        }
    }

    private JsonNode readDsl(String dsl) {
        try {
            return objectMapper.readTree(dsl == null || dsl.isBlank() ? "{}" : dsl);
        } catch (JsonProcessingException e) {
            // Stored value is jsonb so it is always valid JSON; treat a parse failure as a server
            // fault rather than returning a corrupt body.
            throw new BusinessException(ResponseCode.SystemError, "Stored report dsl is not valid JSON", e);
        }
    }

    private ReportDefinitionResponse toResponse(ReportEntity entity) {
        ReportDefinitionResponse dto = new ReportDefinitionResponse();
        dto.setPid(entity.getPid());
        dto.setCode(entity.getCode());
        dto.setTitle(entity.getTitle());
        dto.setProfile(entity.getProfile());
        dto.setStatus(entity.getStatus());
        dto.setVersion(entity.getVersion());
        dto.setDsl(readDsl(entity.getDsl()));
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private ReportDefinitionSummary toSummary(ReportEntity entity) {
        ReportDefinitionSummary dto = new ReportDefinitionSummary();
        dto.setPid(entity.getPid());
        dto.setCode(entity.getCode());
        dto.setTitle(entity.getTitle());
        dto.setStatus(entity.getStatus());
        dto.setVersion(entity.getVersion());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
