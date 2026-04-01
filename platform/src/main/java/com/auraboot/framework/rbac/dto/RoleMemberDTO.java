package com.auraboot.framework.rbac.dto;

import java.time.Instant;

/**
 * DTO representing a member assigned to a role, enriched with org employee info.
 */
public record RoleMemberDTO(
    Long memberId,
    String memberPid,
    String userName,
    String email,
    String departmentName,
    String positionName,
    Instant assignedAt
) {}
