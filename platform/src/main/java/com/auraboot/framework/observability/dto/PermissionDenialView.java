package com.auraboot.framework.observability.dto;

import com.auraboot.framework.permission.entity.PermissionAuditLog;

import java.time.Instant;

/**
 * One permission DENY, shaped for the eagle-eye console.
 *
 * <p>A projection rather than the raw {@code PermissionAuditLog} entity on purpose: the
 * entity carries {@code id}, {@code recordId} and {@code memberId} as {@code Long}, and
 * pushing those across the browser boundary is the public-record-id red line — 19-digit
 * snowflakes also lose precision as JSON numbers. The console needs to show which
 * resource/action was refused and why, so nothing here is an internal id: the record is
 * identified by its pid, and {@code memberId} travels as string digits because the panel
 * only ever displays it.
 *
 * <p>{@code evaluationTrace} is deliberately omitted. It is the full step-by-step policy
 * evaluation including rule internals, which is a different (and much heavier) question
 * than "what got refused on this request" — {@code GET /api/permissions/audit} remains the
 * place to go for it.
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
