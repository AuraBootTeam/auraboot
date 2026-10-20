package com.auraboot.framework.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;

/**
 * DTO representing a member assigned to a role, enriched with org employee info.
 */
public record RoleMemberDTO(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Legacy numeric member identifier; browser consumers use memberPid") Long memberId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String memberPid,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String userName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String email,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String departmentName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String positionName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Instant assignedAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) LocalDate effectiveDate,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) LocalDate expiryDate
) {}
