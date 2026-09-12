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
import java.util.Map;
import com.auraboot.framework.versioning.service.VersionHistoryService;
import com.auraboot.framework.versioning.dto.DesignVersionDTO;
import org.springframework.transaction.annotation.Transactional;
import java.util.stream.Collectors;

/**
 * Canonical CRUD API for low-code report definitions in ab_report.
 * Reads and writes are tenant-scoped and permission-guarded.
 * The DSL travels as a JSON object and is stored in the entity's jsonb column.
 */
@Slf4j
@RestController
@RequestMapping("/api/report-definitions")
@RequiredArgsConstructor
@Tag(name = "Report Definitions", description = "CRUD for first-class low-code report definitions (ab_report)")
public class ReportDefinitionController {

    private final ReportStorageService reportStorageService;
    private final ObjectMapper objectMapper;
    private final VersionHistoryService versionHistoryService;

    @PostMapping
    @Transactional
    @Operation(summary = "Create a report definition", description = "Persists a new ab_report row and returns the minted pid")
    @RequirePermission(MetaPermission.REPORT_DEFINITION_MANAGE)
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
        versionHistoryService.recordVersion("report", created.getPid(), "create", null);
        return ApiResponse.success(toResponse(created));
    }

    @PutMapping("/{pid}")
    @Transactional
    @Operation(summary = "Upsert a report definition",
            description = "Idempotent upsert by pid: updates an existing ab_report row, or creates one with the "
                    + "supplied pid if it does not exist (REST-idempotent). Uses the report-definition store.")
    @RequirePermission(MetaPermission.REPORT_DEFINITION_MANAGE)
    public ApiResponse<ReportDefinitionResponse> upsert(@PathVariable String pid,
                                                        @Valid @RequestBody ReportDefinitionUpdateRequest request) {
        ReportEntity existing = reportStorageService.findByPid(pid);
        if (existing != null && !MetaContext.getCurrentTenantId().equals(existing.getTenantId())) {
            // A live row with this pid belongs to another tenant — do not leak/overwrite it; behave
            // as not-found (same as requireOwned), never cross-tenant upsert.
            throw new BusinessException(ResponseCode.NOT_FOUND, "Report not found: " + pid);
        }

        ReportEntity report = new ReportEntity();
        report.setPid(pid);
        report.setTenantId(MetaContext.getCurrentTenantId());
        // code is consumed only on the create branch (immutable on update); fall back to pid so a
        // missing-row create still satisfies the NOT NULL, tenant-unique code column.
        report.setCode(existing != null ? existing.getCode()
                : (request.getCode() != null && !request.getCode().isBlank() ? request.getCode() : pid));
        // title/profile patch semantics preserved: keep the existing value when the patch is null.
        report.setTitle(request.getTitle() != null ? request.getTitle()
                : (existing != null ? existing.getTitle() : null));
        report.setProfile(request.getProfile() != null ? request.getProfile()
                : (existing != null ? existing.getProfile() : null));
        report.setStatus(request.getStatus());
        report.setDsl(writeDsl(request.getDsl()));
        report.setCreatedBy(existing != null ? existing.getCreatedBy() : MetaContext.getCurrentUserId());
        report.setUpdatedBy(MetaContext.getCurrentUserId());

        ReportEntity saved = reportStorageService.upsertByPid(report);
        versionHistoryService.recordVersion("report", saved.getPid(), existing == null ? "create" : "update", null);
        return ApiResponse.success(toResponse(saved));
    }

    @GetMapping("/{pid}")
    @Operation(summary = "Get a report definition", description = "Loads one live ab_report row (404 if not found / soft-deleted)")
    @RequirePermission(MetaPermission.REPORT_DEFINITION_VIEW)
    public ApiResponse<ReportDefinitionResponse> get(@PathVariable String pid) {
        return ApiResponse.success(toResponse(requireOwned(pid)));
    }

    @GetMapping("/by-code/{code}")
    @Operation(summary = "Get a report definition by code",
            description = "Loads one live ab_report row by its tenant-unique code (== the report's pageKey); "
                    + "404 if not found / soft-deleted.")
    @RequirePermission(MetaPermission.REPORT_DEFINITION_VIEW)
    public ApiResponse<ReportDefinitionResponse> getByCode(@PathVariable String code) {
        return ApiResponse.success(toResponse(requireOwnedByCode(code)));
    }

    @GetMapping
    @Operation(summary = "List report definitions", description = "Lists the current tenant's live reports (lightweight: no dsl)")
    @RequirePermission(MetaPermission.REPORT_DEFINITION_VIEW)
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
    @RequirePermission(MetaPermission.REPORT_DEFINITION_MANAGE)
    public ApiResponse<Void> delete(@PathVariable String pid) {
        requireOwned(pid);
        reportStorageService.softDelete(pid);
        return ApiResponse.success();
    }

    @GetMapping("/{pid}/versions")
    @RequirePermission(MetaPermission.REPORT_DEFINITION_VIEW)
    public ApiResponse<List<DesignVersionDTO>> history(@PathVariable String pid) {
        requireOwned(pid);
        return ApiResponse.success(versionHistoryService.getHistory("report", pid));
    }

    @GetMapping("/{pid}/versions/count")
    @RequirePermission(MetaPermission.REPORT_DEFINITION_VIEW)
    public ApiResponse<Map<String, Integer>> versionCount(@PathVariable String pid) {
        requireOwned(pid);
        return ApiResponse.success(Map.of("count", versionHistoryService.countVersions("report", pid)));
    }

    @GetMapping("/{pid}/versions/{versionPid}")
    @RequirePermission(MetaPermission.REPORT_DEFINITION_VIEW)
    public ApiResponse<DesignVersionDTO> version(@PathVariable String pid, @PathVariable String versionPid) {
        requireOwned(pid);
        return ApiResponse.success(requireReportVersion(pid, versionPid));
    }

    @PostMapping("/{pid}/versions/{versionPid}/rollback")
    @RequirePermission(MetaPermission.REPORT_DEFINITION_MANAGE)
    public ApiResponse<DesignVersionDTO> rollback(@PathVariable String pid, @PathVariable String versionPid) {
        requireOwned(pid);
        requireReportVersion(pid, versionPid);
        return ApiResponse.success(versionHistoryService.rollback("report", pid, versionPid));
    }

    private DesignVersionDTO requireReportVersion(String pid, String versionPid) {
        DesignVersionDTO version = versionHistoryService.getVersion(versionPid);
        if (version == null || !"report".equals(version.getResourceType()) || !pid.equals(version.getResourceId())) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Report version not found");
        }
        return version;
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

    /**
     * Load a live report by its tenant-unique code, else 404. The storage finder is already
     * tenant-scoped (it queries by the current tenant id), so a cross-tenant code never resolves;
     * a soft-deleted row is excluded by the {@code @TableLogic} interceptor.
     */
    private ReportEntity requireOwnedByCode(String code) {
        ReportEntity entity = reportStorageService.findByCode(MetaContext.getCurrentTenantId(), code);
        if (entity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Report not found: " + code);
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
