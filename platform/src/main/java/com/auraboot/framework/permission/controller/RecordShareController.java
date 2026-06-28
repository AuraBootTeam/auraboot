package com.auraboot.framework.permission.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.entity.RecordShare;
import com.auraboot.framework.permission.service.RecordShareService;
import com.auraboot.framework.permission.service.UserPermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    private final DynamicDataService dynamicDataService;
    private final UserPermissionService userPermissionService;

    /**
     * List all active shares for a record.
     *
     * @param resourceCode model/resource code (e.g. "crm_opportunity")
     * @param recordPid stable public record PID
     * @return list of share entries
     */
    @GetMapping
    @Operation(summary = "List shares for a record")
    public ApiResponse<List<RecordShare>> listShares(
            @RequestParam @NotBlank String resourceCode,
            @RequestParam @NotBlank String recordPid) {

        Long tenantId = MetaContext.getCurrentTenantId();
        log.debug("Listing shares: resourceCode={}, recordPid={}, tenantId={}",
                resourceCode, recordPid, tenantId);

        List<RecordShare> shares = recordShareService.listByRecordPid(tenantId, resourceCode, recordPid);
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
        log.info("Sharing record: resourceCode={}, recordPid={}, subjectType={}, subjectId={}, subjectPid={}, tenantId={}",
                request.getResourceCode(), request.getRecordPid(),
                request.getSubjectType(), request.getSubjectId(), request.getSubjectPid(), tenantId);

        if (!StringUtils.hasText(request.getRecordPid())) {
            throw new RootUnCheckedException(BadParam, "recordPid is required");
        }
        if (request.getSubjectId() == null && !StringUtils.hasText(request.getSubjectPid())) {
            throw new RootUnCheckedException(BadParam, "subjectId or subjectPid is required");
        }
        assertCanManageRecordShares(request.getResourceCode(), request.getRecordPid());
        recordShareService.shareRecordByPid(
                tenantId,
                request.getResourceCode(),
                request.getRecordPid(),
                request.getSubjectType(),
                request.getSubjectId(),
                request.getSubjectPid(),
                request.getPermissionMask(),
                request.getExpiresAt());

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

        RecordShare share = recordShareService.getByIdInTenant(tenantId, shareId);
        if (share == null) {
            throw new AccessDeniedException("Record share not found or not accessible");
        }
        authorizeShareRemoval(share);
        recordShareService.removeById(tenantId, shareId);
        return ApiResponse.success();
    }

    // -----------------------------------------------------------------------
    // Authorization (audit 2026-06-28: record sharing must not be tenant-wide open)
    // -----------------------------------------------------------------------

    /**
     * Authorize creation of a share on a record. The caller must either hold the
     * {@code data.record_share.manage} administration permission, or be the owner
     * (creator) of the target record. Otherwise {@link AccessDeniedException} (HTTP 403)
     * is thrown. Closes the within-tenant escalation hole where any member could grant
     * themselves access to records they cannot see.
     */
    private void assertCanManageRecordShares(String resourceCode, String recordPid) {
        Long callerId = MetaContext.getCurrentUserId();
        if (callerId == null) {
            throw new AccessDeniedException("Authenticated user required to manage record shares");
        }
        if (userPermissionService.hasPermission(callerId, MetaPermission.RECORD_SHARE_MANAGE)) {
            return; // administrative escape hatch
        }
        if (isRecordOwner(resourceCode, recordPid, callerId)) {
            return; // a record owner may share their own record
        }
        log.warn("Denied record-share mutation: caller={} is neither owner nor admin for {}/{}",
                callerId, resourceCode, recordPid);
        throw new AccessDeniedException(
                "Only the record owner or an authorized administrator can manage shares for this record");
    }

    /**
     * Authorize removal of an existing share. Allowed for the administrator, the original
     * creator of the share, or the owner of the underlying record.
     */
    private void authorizeShareRemoval(RecordShare share) {
        Long callerId = MetaContext.getCurrentUserId();
        if (callerId == null) {
            throw new AccessDeniedException("Authenticated user required to manage record shares");
        }
        if (userPermissionService.hasPermission(callerId, MetaPermission.RECORD_SHARE_MANAGE)) {
            return;
        }
        if (callerId.equals(share.getCreatedBy())) {
            return; // the member who created the share may revoke it
        }
        if (isRecordOwner(share.getResourceCode(), share.getRecordPid(), callerId)) {
            return;
        }
        log.warn("Denied record-share removal: caller={} for shareId={} ({}/{})",
                callerId, share.getId(), share.getResourceCode(), share.getRecordPid());
        throw new AccessDeniedException(
                "Only the record owner, the share creator, or an authorized administrator can remove this share");
    }

    /**
     * Returns true if {@code callerId} created the target record (its {@code created_by}).
     * If the record cannot be resolved (missing / not accessible) this returns false so
     * that sharing fails closed.
     */
    private boolean isRecordOwner(String resourceCode, String recordPid, Long callerId) {
        if (!StringUtils.hasText(resourceCode) || !StringUtils.hasText(recordPid)) {
            return false;
        }
        Map<String, Object> record = dynamicDataService.getById(resourceCode, recordPid);
        if (record == null || record.isEmpty()) {
            return false;
        }
        Long ownerId = toLong(record.get("created_by"));
        return ownerId != null && ownerId.equals(callerId);
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException ex) {
            // Non-numeric created_by → treat as no resolvable owner (fail closed).
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Inner DTO
    // -----------------------------------------------------------------------

    @Data
    public static class RecordShareRequest {

        /** Model/resource code (e.g. "crm_opportunity") */
        @NotBlank
        private String resourceCode;

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
