package com.auraboot.framework.view.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

/** Existing membership keys exposed to the SavedView team picker. */
public record SavedViewTeamMembershipDTO(
        String teamPid,
        @Schema(nullable = true) String teamName,
        @Schema(nullable = true) String teamCode,
        @Schema(nullable = true) String role,
        @Schema(nullable = true) Instant joinedAt) {
    public static SavedViewTeamMembershipDTO from(Map<String, Object> membership) {
        return new SavedViewTeamMembershipDTO(
                String.class.cast(membership.get("teamPid")),
                String.class.cast(membership.get("teamName")),
                String.class.cast(membership.get("teamCode")),
                String.class.cast(membership.get("role")),
                Instant.class.cast(membership.get("joinedAt")));
    }
}
