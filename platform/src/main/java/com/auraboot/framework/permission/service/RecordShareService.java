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
     * Share a record identified by stable public PID with a subject identified by PID.
     *
     * @param tenantId       tenant ID
     * @param resourceCode   model/resource code
     * @param recordPid      stable public record PID
     * @param subjectType    subject type ("member", "role", "dept")
     * @param subjectPid     stable public subject PID
     * @param permissionMask optional permission mask (e.g. "read", "read,update")
     * @param expiresAt      optional expiration time
     */
    default void shareRecordByPid(Long tenantId, String resourceCode, String recordPid,
                                  String subjectType, String subjectPid,
                                  String permissionMask, Instant expiresAt) {
        shareRecordByPid(tenantId, resourceCode, recordPid, subjectType, null, subjectPid, permissionMask, expiresAt);
    }

    /**
     * Share a record identified by stable public PID while optionally retaining a legacy subject ID.
     */
    void shareRecordByPid(Long tenantId, String resourceCode, String recordPid,
                          String subjectType, Long subjectId, String subjectPid,
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
    default boolean isShared(Long tenantId, String resourceCode, Long recordId, Long memberId) {
        return isShared(tenantId, resourceCode, recordId, memberId, "read");
    }

    /**
     * Check whether the exact action is granted by the record share permission mask.
     */
    boolean isShared(Long tenantId, String resourceCode, Long recordId, Long memberId, String action);

    /**
     * Check whether read access to a PID-addressed record is shared with a member.
     */
    default boolean isSharedByPid(
            Long tenantId,
            String resourceCode,
            String recordPid,
            Long memberId,
            String memberPid) {
        return isSharedByPid(tenantId, resourceCode, recordPid, memberId, memberPid, "read");
    }

    /**
     * Check whether the exact action on a PID-addressed record is shared with a member.
     */
    boolean isSharedByPid(
            Long tenantId,
            String resourceCode,
            String recordPid,
            Long memberId,
            String memberPid,
            String action);

    /**
     * Get all record IDs shared with a member (directly or via their roles).
     *
     * @param tenantId     tenant ID
     * @param resourceCode model/resource code
     * @param memberId     member (user) ID
     * @param action       action that must be present in the share permission mask
     * @return list of record IDs
     */
    List<Long> getSharedRecordIds(Long tenantId, String resourceCode, Long memberId, String action);

    /**
     * Get all public record PIDs shared directly with a member or through one of their roles.
     */
    List<String> getSharedRecordPids(
            Long tenantId,
            String resourceCode,
            Long memberId,
            String memberPid,
            String action);

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
     * List all shares for a specific record PID.
     */
    java.util.List<com.auraboot.framework.permission.entity.RecordShare> listByRecordPid(
            Long tenantId, String resourceCode, String recordPid);

    /**
     * List every share relationship for management, including expired grants.
     * Expired grants remain visible to the owner so they can be renewed or removed,
     * while all access-evaluation methods continue to exclude them.
     */
    java.util.List<com.auraboot.framework.permission.entity.RecordShare> listByRecordPidForManagement(
            Long tenantId, String resourceCode, String recordPid);

    /** Update the permission mask and optional expiry through the public share PID. */
    void updateByPid(
            Long tenantId,
            String sharePid,
            String permissionMask,
            Instant expiresAt);

    /** Remove a share by its stable public PID, scoped to the tenant. */
    void removeByPid(Long tenantId, String sharePid);

    /** Look up a share by its stable public PID, scoped to the tenant. */
    com.auraboot.framework.permission.entity.RecordShare getByPidInTenant(Long tenantId, String sharePid);
}
