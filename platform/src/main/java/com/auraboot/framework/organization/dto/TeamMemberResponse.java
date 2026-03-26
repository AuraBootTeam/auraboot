package com.auraboot.framework.organization.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class TeamMemberResponse {

    private String pid;
    private Long userId;
    private String userName;
    private String userEmail;
    private String role;
    private Instant joinedAt;
}
