package com.auraboot.framework.observability.dto;

import com.auraboot.framework.permission.entity.PermissionAuditLog;

import java.time.Instant;

/**
 * Permission denial projection for trace correlation.
 *
 * <p>Internal numeric ids and the full policy evaluation trace are deliberately
 * excluded. Public record pid is retained, and member id is serialized as a
 * string to avoid JavaScript precision loss.
 */
public record PermissionDenialView(
        String resourceCode,
        String actionCode,
        String reason,
        String recordPid,
        String memberId,
        Instant createdAt) {

    public static PermissionDenialView from(PermissionAuditLog row) {
        return new PermissionDenialView(
                row.getResourceCode(),
                row.getActionCode(),
                row.getReason(),
                row.getRecordPid(),
                row.getMemberId() == null ? null : String.valueOf(row.getMemberId()),
                row.getCreatedAt());
    }
}
