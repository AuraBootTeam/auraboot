package com.auraboot.framework.organization.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class TeamResponse {

    private String pid;
    private String code;
    private String name;
    private String description;
    private String leaderId;
    private String leaderName;
    private String status;
    private int memberCount;
    private Instant createdAt;
    private Instant updatedAt;
}
