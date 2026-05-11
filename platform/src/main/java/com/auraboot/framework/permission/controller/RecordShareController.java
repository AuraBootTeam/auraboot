package com.auraboot.framework.permission.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.permission.entity.RecordShare;
import com.auraboot.framework.permission.service.RecordShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

import static com.auraboot.framework.common.constant.ResponseCode.BadParam;

/**
 * RecordShare Controller — REST API for per-record sharing (ReBAC).
 *
 * <p>Allows listing, creating, and removing shares for individual records.
 * Tenant isolation is enforced via MetaContext.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET  /api/record-share  - List shares for a record</li>
 *   <li>POST /api/record-share  - Share a record with a subject</li>
 *   <li>DELETE /api/record-share/{shareId} - Remove a share by ID</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/record-share")
@RequiredArgsConstructor
@Validated
@Tag(name = "Record Share", description = "Per-record sharing (ReBAC)")
public class RecordShareController {

    private final RecordShareService recordShareService;

    /**
     * List all active shares for a record.
     *
     * @param resourceCode model/resource code (e.g. "crm_opportunity")
     * @param recordId     legacy numeric record ID
     * @param recordPid    stable public record PID
     * @return list of share entries
     */
    @GetMapping
    @Operation(summary = "List shares for a record")
    public ApiResponse<List<RecordShare>> listShares(
            @RequestParam @NotBlank String resourceCode,
            @RequestParam(required = false) Long recordId,
            @RequestParam(required = false) String recordPid) {

        Long tenantId = MetaContext.getCurrentTenantId();
        log.debug("Listing shares: resourceCode={}, recordId={}, recordPid={}, tenantId={}",
                resourceCode, recordId, recordPid, tenantId);

        List<RecordShare> shares;
        if (StringUtils.hasText(recordPid)) {
            shares = recordShareService.listByRecordPid(tenantId, resourceCode, recordPid);
        } else if (recordId != null) {
            shares = recordShareService.listByRecord(tenantId, resourceCode, recordId);
        } else {
            throw new RootUnCheckedException(BadParam, "recordId or recordPid is required");
        }
        return ApiResponse.success(shares);
    }

    /**
     * Share a record with a subject (member, role, or dept).
     *
     * @param request share request body
     * @return success
     */
    @PostMapping
    @Operation(summary = "Share a record with a subject")
    public ApiResponse<Void> shareRecord(@Valid @RequestBody RecordShareRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        log.info("Sharing record: resourceCode={}, recordId={}, recordPid={}, subjectType={}, subjectId={}, subjectPid={}, tenantId={}",
                request.getResourceCode(), request.getRecordId(), request.getRecordPid(),
                request.getSubjectType(), request.getSubjectId(), request.getSubjectPid(), tenantId);

        if (StringUtils.hasText(request.getRecordPid())) {
            if (request.getSubjectId() == null && !StringUtils.hasText(request.getSubjectPid())) {
                throw new RootUnCheckedException(BadParam, "subjectId or subjectPid is required");
            }
            recordShareService.shareRecordByPid(
                    tenantId,
                    request.getResourceCode(),
                    request.getRecordPid(),
                    request.getSubjectType(),
                    request.getSubjectId(),
                    request.getSubjectPid(),
                    request.getPermissionMask(),
                    request.getExpiresAt());
        } else {
            if (request.getRecordId() == null) {
                throw new RootUnCheckedException(BadParam, "recordId or recordPid is required");
            }
            if (request.getSubjectId() == null) {
                throw new RootUnCheckedException(BadParam, "subjectId is required when sharing by recordId");
            }
            recordShareService.shareRecord(
                    tenantId,
                    request.getResourceCode(),
                    request.getRecordId(),
                    request.getSubjectType(),
                    request.getSubjectId(),
                    request.getPermissionMask(),
                    request.getExpiresAt());
        }

        return ApiResponse.success();
    }

    /**
     * Remove a share by its ID.
     *
     * @param shareId the ID of the share entry to remove
     * @return success
     */
    @DeleteMapping("/{shareId}")
    @Operation(summary = "Remove a share by ID")
    public ApiResponse<Void> removeShare(@PathVariable @NotNull Long shareId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        log.info("Removing share: shareId={}, tenantId={}", shareId, tenantId);

        recordShareService.removeById(tenantId, shareId);
        return ApiResponse.success();
    }

    // -----------------------------------------------------------------------
    // Inner DTO
    // -----------------------------------------------------------------------

    @Data
    public static class RecordShareRequest {

        /** Model/resource code (e.g. "crm_opportunity") */
        @NotBlank
        private String resourceCode;

        /** Legacy numeric record ID */
        private Long recordId;

        /** Stable public record PID */
        private String recordPid;

        /** Subject type: "member", "role", or "dept" */
        @NotBlank
        private String subjectType;

        /** Legacy subject ID (member ID, role ID, or dept ID) */
        private Long subjectId;

        /** Stable public subject PID */
        private String subjectPid;

        /** Optional permission mask (e.g. "read", "read,update"). Defaults to "read". */
        private String permissionMask = "read";

        /** Optional expiration time (ISO-8601). Null means no expiry. */
        private Instant expiresAt;
    }
}
