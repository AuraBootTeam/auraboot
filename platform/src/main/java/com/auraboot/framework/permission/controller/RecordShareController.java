package com.auraboot.framework.permission.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.notification.service.NotificationService;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.entity.RecordShare;
import com.auraboot.framework.permission.service.RecordShareService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.user.dto.UserSearchDTO;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.auraboot.framework.common.constant.ResponseCode.BadParam;

/**
 * RecordShare Controller — REST API for per-record sharing (ReBAC).
 *
 * <p>Allows listing, creating, updating, and removing shares for individual records.
 * Tenant isolation is enforced via MetaContext.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET  /api/record-share  - List shares for a record</li>
 *   <li>POST /api/record-share  - Share a record with a subject</li>
 *   <li>PATCH /api/record-share/{sharePid} - Update a share by public PID</li>
 *   <li>DELETE /api/record-share/{sharePid} - Remove a share by public PID</li>
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
    private final MetaModelService metaModelService;
    private final UserService userService;
    private final NotificationService notificationService;
    private final I18nService i18nService;

    private static final Set<String> PUBLIC_PERMISSION_MASKS = Set.of("read", "read,update");

    /**
     * List all shares for management, including expired relationships that can be renewed.
     *
     * @param resourceCode model/resource code (e.g. "crm_opportunity_common")
     * @param recordPid stable public record PID
     * @return list of share entries
     */
    @GetMapping
    @Operation(summary = "List shares for a record")
    public ApiResponse<List<RecordShareResponse>> listShares(
            @RequestParam @NotBlank String resourceCode,
            @RequestParam @NotBlank String recordPid) {

        Long tenantId = MetaContext.getCurrentTenantId();
        assertCanManageRecordShares(resourceCode, recordPid);
        log.debug("Listing shares: resourceCode={}, recordPid={}, tenantId={}",
                resourceCode, recordPid, tenantId);

        List<RecordShareResponse> shares = recordShareService
                .listByRecordPidForManagement(tenantId, resourceCode, recordPid)
                .stream()
                .map(share -> toResponse(tenantId, share))
                .toList();
        return ApiResponse.success(shares);
    }

    /** Return whether the authenticated caller may manage this record's collaborators. */
    @GetMapping("/manage-capability")
    @Operation(summary = "Get record-share management capability")
    public ApiResponse<RecordShareCapabilityResponse> getManageCapability(
            @RequestParam @NotBlank String resourceCode,
            @RequestParam @NotBlank String recordPid) {
        return ApiResponse.success(new RecordShareCapabilityResponse(
                canManageRecordShares(resourceCode, recordPid)));
    }

    /**
     * Share a record with another active member of the current tenant.
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
                request.getSubjectType(), null, request.getSubjectPid(), tenantId);

        if (!StringUtils.hasText(request.getRecordPid())) {
            throw new RootUnCheckedException(BadParam, "recordPid is required");
        }
        if (!"member".equalsIgnoreCase(request.getSubjectType())) {
            throw new RootUnCheckedException(BadParam, "Public record sharing currently supports tenant members only");
        }
        if (!StringUtils.hasText(request.getSubjectPid())) {
            throw new RootUnCheckedException(BadParam, "subjectPid is required");
        }
        String permissionMask = normalizePermissionMask(request.getPermissionMask());
        if (!PUBLIC_PERMISSION_MASKS.contains(permissionMask)) {
            throw new RootUnCheckedException(BadParam, "permissionMask must be read or read,update");
        }
        assertFutureExpiry(request.getExpiresAt());
        assertCanManageRecordShares(request.getResourceCode(), request.getRecordPid());
        UserSearchDTO subject = userService.findInTenantByPid(tenantId, request.getSubjectPid().trim());
        if (subject == null) {
            throw new RootUnCheckedException(BadParam, "Share subject is not an active member of this tenant");
        }
        User recipient = userService.findByPid(subject.getPid());
        if (recipient == null || recipient.getId() == null
                || !(recipient.getUserType() == null || "human".equalsIgnoreCase(recipient.getUserType()))
                || !recipient.isEnabled()) {
            throw new RootUnCheckedException(BadParam, "Share subject must be an enabled human member");
        }
        recordShareService.shareRecordByPid(
                tenantId,
                request.getResourceCode(),
                request.getRecordPid(),
                "member",
                subject.getPid(),
                permissionMask,
                request.getExpiresAt());
        notifyShareRecipient(recipient, request.getResourceCode(), request.getRecordPid(), permissionMask);

        return ApiResponse.success();
    }

    /**
     * Update an existing relationship by public share PID. This avoids returning a
     * subject PID to the browser merely so an owner can renew an expired grant.
     */
    @PatchMapping("/{sharePid}")
    @Operation(summary = "Update a record share policy by public PID")
    public ApiResponse<Void> updateShare(
            @PathVariable @NotBlank String sharePid,
            @Valid @RequestBody RecordShareUpdateRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        RecordShare share = recordShareService.getByPidInTenant(tenantId, sharePid);
        if (share == null) {
            throw new AccessDeniedException("Record share not found or not accessible");
        }
        authorizeExistingShareManagement(share);
        String permissionMask = normalizePermissionMask(request.getPermissionMask());
        if (!PUBLIC_PERMISSION_MASKS.contains(permissionMask)) {
            throw new RootUnCheckedException(BadParam, "permissionMask must be read or read,update");
        }
        assertFutureExpiry(request.getExpiresAt());
        recordShareService.updateByPid(
                tenantId, sharePid, permissionMask, request.getExpiresAt());
        notifyShareRecipientByPid(
                tenantId,
                share.getSubjectPid(),
                share.getResourceCode(),
                share.getRecordPid(),
                permissionMask);
        return ApiResponse.success();
    }

    /**
     * Remove a share by its stable public PID.
     *
     * @param sharePid stable public PID of the share entry
     * @return success
     */
    @DeleteMapping("/{sharePid}")
    @Operation(summary = "Remove a share by public PID")
    public ApiResponse<Void> removeShare(@PathVariable @NotBlank String sharePid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        log.info("Removing share: sharePid={}, tenantId={}", sharePid, tenantId);

        RecordShare share = recordShareService.getByPidInTenant(tenantId, sharePid);
        if (share == null) {
            throw new AccessDeniedException("Record share not found or not accessible");
        }
        authorizeExistingShareManagement(share);
        recordShareService.removeByPid(tenantId, sharePid);
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
        if (canManageRecordShares(resourceCode, recordPid)) {
            return;
        }
        Long callerId = MetaContext.getCurrentUserId();
        if (callerId == null) {
            throw new AccessDeniedException("Authenticated user required to manage record shares");
        }
        log.warn("Denied record-share mutation: caller={} is neither owner nor admin for {}/{}",
                callerId, resourceCode, recordPid);
        throw new AccessDeniedException(
                "Only the record owner or an authorized administrator can manage shares for this record");
    }

    private boolean canManageRecordShares(String resourceCode, String recordPid) {
        Long callerId = MetaContext.getCurrentUserId();
        if (callerId == null || !StringUtils.hasText(resourceCode) || !StringUtils.hasText(recordPid)) {
            return false;
        }
        Map<String, Object> record;
        try {
            record = dynamicDataService.getById(resourceCode, recordPid);
        } catch (MetaServiceException error) {
            if (!isRecordUnavailable(error)) throw error;
            log.debug("Record-share capability unavailable for inaccessible or deleted record: caller={}, resource={}/{}",
                    callerId, resourceCode, recordPid);
            return false;
        }
        if (record == null || record.isEmpty()) {
            return false;
        }
        return userPermissionService.hasPermission(callerId, MetaPermission.RECORD_SHARE_MANAGE)
                || isRecordOwner(resourceCode, record, callerId, MetaContext.getCurrentUserPid());
    }

    private static boolean isRecordUnavailable(MetaServiceException error) {
        String message = error.getMessage();
        return message != null
                && (message.startsWith("Access denied:") || message.startsWith("Record not found:"));
    }

    /**
     * Authorize updating or removing an existing share. Allowed for an administrator or
     * the owner of the underlying record.
     */
    private void authorizeExistingShareManagement(RecordShare share) {
        Long callerId = MetaContext.getCurrentUserId();
        if (callerId == null) {
            throw new AccessDeniedException("Authenticated user required to manage record shares");
        }
        if (userPermissionService.hasPermission(callerId, MetaPermission.RECORD_SHARE_MANAGE)) {
            return;
        }
        Map<String, Object> record = dynamicDataService.getById(
                share.getResourceCode(), share.getRecordPid());
        if (record != null && !record.isEmpty() && isRecordOwner(
                share.getResourceCode(), record, callerId, MetaContext.getCurrentUserPid())) {
            return;
        }
        log.warn("Denied existing record-share management: caller={} for sharePid={} ({}/{})",
                callerId, share.getPid(), share.getResourceCode(), share.getRecordPid());
        throw new AccessDeniedException(
                "Only the record owner or an authorized administrator can manage this share");
    }

    /**
     * Returns true if the caller matches the model's configured business owner field.
     * Models without a data-scope owner field fall back to {@code created_by}.
     * If the record cannot be resolved (missing / not accessible) this returns false so
     * that sharing fails closed.
     */
    private boolean isRecordOwner(
            String resourceCode, Map<String, Object> record, Long callerId, String callerPid) {
        if (!StringUtils.hasText(resourceCode) || record == null || record.isEmpty()) {
            return false;
        }
        String ownerField;
        try {
            ownerField = resolveOwnerField(resourceCode);
        } catch (RuntimeException ex) {
            log.warn("Denied record-share management because owner metadata for {} is unavailable: {}",
                    resourceCode, ex.getMessage());
            return false;
        }
        if (!StringUtils.hasText(ownerField)) {
            return false;
        }
        Object owner = record.get(ownerField);
        if (owner == null) {
            return false;
        }
        String ownerValue = String.valueOf(owner).trim();
        return (StringUtils.hasText(callerPid) && callerPid.trim().equals(ownerValue))
                || String.valueOf(callerId).equals(ownerValue);
    }

    private String resolveOwnerField(String resourceCode) {
        MetaModelDTO model = metaModelService.findByCode(resourceCode);
        if (model == null) {
            return null;
        }
        if (model.getExtension() != null
                && model.getExtension().get("dataScope") instanceof Map<?, ?> dataScope) {
            Object configured = dataScope.get("ownerField");
            if (configured != null && StringUtils.hasText(String.valueOf(configured))) {
                return String.valueOf(configured).trim();
            }
        }
        return "created_by";
    }

    private RecordShareResponse toResponse(Long tenantId, RecordShare share) {
        String subjectName = null;
        if ("member".equalsIgnoreCase(share.getSubjectType()) && StringUtils.hasText(share.getSubjectPid())) {
            UserSearchDTO member = userService.findInTenantByPid(tenantId, share.getSubjectPid());
            subjectName = member != null ? member.getDisplayName() : null;
        }
        return new RecordShareResponse(
                share.getPid(),
                share.getSubjectType(),
                subjectName,
                share.getPermissionMask(),
                share.getExpiresAt(),
                share.getCreatedAt());
    }

    /**
     * Notify the collaborator after access has been granted or updated. Notification delivery is
     * deliberately best-effort: a transient notification failure must not roll back valid access.
     */
    private void notifyShareRecipient(
            User recipient, String resourceCode, String recordPid, String permissionMask) {
        try {
            String locale = LocaleContextHolder.getLocale().toLanguageTag();
            String permissionLabel = i18nService.getValue(
                    locale,
                    "record_share.notification_permission_"
                            + ("read,update".equals(permissionMask) ? "collaborate" : "read"),
                    "read,update".equals(permissionMask) ? "collaboration" : "view-only");
            String title = i18nService.getValue(
                    locale, "record_share.notification_title", "Record collaboration updated");
            String content = i18nService.getMessage(
                    locale, "record_share.notification_content", permissionLabel);
            if (!StringUtils.hasText(content)) {
                content = "You now have " + permissionLabel + " access to a shared record.";
            }
            notificationService.sendInApp(
                    recipient.getId(), title, content, "business", resourceCode, recordPid);
        } catch (RuntimeException ex) {
            log.warn("Record share saved but collaborator notification failed for {}/{}: {}",
                    resourceCode, recordPid, ex.getMessage());
        }
    }

    private void notifyShareRecipientByPid(
            Long tenantId,
            String subjectPid,
            String resourceCode,
            String recordPid,
            String permissionMask) {
        if (!StringUtils.hasText(subjectPid)) {
            return;
        }
        UserSearchDTO member = userService.findInTenantByPid(tenantId, subjectPid);
        if (member == null) {
            return;
        }
        User recipient = userService.findByPid(member.getPid());
        if (recipient != null && recipient.getId() != null && recipient.isEnabled()) {
            notifyShareRecipient(recipient, resourceCode, recordPid, permissionMask);
        }
    }

    private static void assertFutureExpiry(Instant expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new RootUnCheckedException(BadParam, "expiresAt must be in the future");
        }
    }

    private static String normalizePermissionMask(String permissionMask) {
        return StringUtils.hasText(permissionMask)
                ? permissionMask.trim().toLowerCase().replace(" ", "")
                : "read";
    }

    // -----------------------------------------------------------------------
    // Inner DTO
    // -----------------------------------------------------------------------

    @Data
    public static class RecordShareRequest {

        /** Model/resource code (e.g. "crm_opportunity_common") */
        @NotBlank
        private String resourceCode;

        /** Stable public record PID */
        private String recordPid;

        /** Public subject type. Only "member" is accepted by this endpoint. */
        @NotBlank
        private String subjectType;

        /** Stable public subject PID */
        private String subjectPid;

        /** Optional permission mask (e.g. "read", "read,update"). Defaults to "read". */
        private String permissionMask = "read";

        /** Optional expiration time (ISO-8601). Null means no expiry. */
        private Instant expiresAt;
    }

    @Data
    public static class RecordShareUpdateRequest {

        /** Permission mask (read or read,update). */
        @NotBlank
        private String permissionMask;

        /** Optional future expiration time. Null means no expiry. */
        private Instant expiresAt;
    }

    public record RecordShareResponse(
            String pid,
            String subjectType,
            String subjectName,
            String permissionMask,
            Instant expiresAt,
            Instant createdAt) {
    }

    public record RecordShareCapabilityResponse(boolean canManage) {
    }
}
