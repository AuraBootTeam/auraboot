package com.auraboot.framework.organization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.Instant;

@Data
public class TeamMemberResponse {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String pid;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;
    @Schema(nullable = true)
    private String userPid;
    @Schema(nullable = true)
    private String memberPid;
    @Schema(nullable = true)
    private String userName;
    @Schema(nullable = true)
    private String userEmail;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String role;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant joinedAt;
}
