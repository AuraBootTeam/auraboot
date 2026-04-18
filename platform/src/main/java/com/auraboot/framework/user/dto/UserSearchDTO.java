package com.auraboot.framework.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight user search result DTO exposed by {@code GET /api/admin/users/search}.
 *
 * <p>Intentionally excludes password, reset tokens, locked/failed-attempt counters and any other
 * sensitive fields — this DTO is safe to return to tenant members for picker UIs (member picker,
 * reference field dropdown, approval assignee selection, etc.).</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSearchDTO {

    /** Business ID (ULID) — stable public identifier, safe to use as selection value. */
    private String pid;

    /** Display-friendly name, derived from nick_name / user_name / email in that order. */
    private String displayName;

    /** Email, used for secondary identification in picker list rows. */
    private String email;

    /** Avatar file ID (not a URL) — frontend resolves via {@code /api/files/{id}}. */
    private String avatarUrl;

    /** Department name resolved via mt_org_employee → mt_org_department, nullable. */
    private String departmentName;
}
