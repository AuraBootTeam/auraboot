package com.auraboot.framework.organization.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.Instant;

@Data
public class TeamResponse {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String pid;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;
    @Schema(nullable = true)
    private String description;
    @Schema(nullable = true)
    private String leaderId;
    @Schema(nullable = true)
    private String leaderName;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String status;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private int memberCount;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant createdAt;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant updatedAt;
}
