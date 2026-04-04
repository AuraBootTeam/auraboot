package com.auraboot.framework.permission.service;

import java.time.Instant;
import java.util.List;

/**
 * Record Share Service — manages record-level sharing (ReBAC).
 *
 * <p>Allows sharing individual records with specific users, roles, or departments,
 * bypassing data scope restrictions.
 */
public interface RecordShareService {

    /**
     * Share a record with a subject (user, role, or dept).
     *
     * @param tenantId       tenant ID
     * @param resourceCode   model/resource code
     * @param recordId       record ID
     * @param subjectType    subject type ("member", "role", "dept")
     * @param subjectId      subject ID
     * @param permissionMask optional permission mask (e.g. "read", "read,update")
     * @param expiresAt      optional expiration time
     */
    void shareRecord(Long tenantId, String resourceCode, Long recordId,
                     String subjectType, Long subjectId,
                     String permissionMask, Instant expiresAt);

    /**
     * Remove sharing of a record with a subject.
     *
     * @param tenantId     tenant ID
     * @param resourceCode model/resource code
     * @param recordId     record ID
     * @param subjectType  subject type
     * @param subjectId    subject ID
     */
    void unshareRecord(Long tenantId, String resourceCode, Long recordId,
                       String subjectType, Long subjectId);

    /**
     * Check if a record is shared with a member (directly or via their roles).
     *
     * @param tenantId     tenant ID
     * @param resourceCode model/resource code
     * @param recordId     record ID
     * @param memberId     member (user) ID
     * @return true if the record is shared with this member
     */
    boolean isShared(Long tenantId, String resourceCode, Long recordId, Long memberId);

    /**
     * Get all record IDs shared with a member (directly or via their roles).
     *
     * @param tenantId     tenant ID
     * @param resourceCode model/resource code
     * @param memberId     member (user) ID
     * @param action       action (currently unused, reserved for permission_mask filtering)
     * @return list of record IDs
     */
    List<Long> getSharedRecordIds(Long tenantId, String resourceCode, Long memberId, String action);

    /**
     * List all shares for a specific record.
     *
     * @param tenantId     tenant ID
     * @param resourceCode model/resource code
     * @param recordId     record ID
     * @return list of share entries (non-expired)
     */
    java.util.List<com.auraboot.framework.permission.entity.RecordShare> listByRecord(
            Long tenantId, String resourceCode, Long recordId);

    /**
     * Remove a share by its ID.
     *
     * @param tenantId tenant ID (for security verification)
     * @param shareId  share record ID
     */
    void removeById(Long tenantId, Long shareId);
}
