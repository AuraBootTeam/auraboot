package com.auraboot.framework.organization.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TeamMemberAddRequest {

    @NotNull(message = "User ID is required")
    private Long userId;

    private String role = "member"; // LEADER, MEMBER
}
