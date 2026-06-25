package com.auraboot.framework.organization.dto;

import lombok.Data;

@Data
public class TeamMemberAddRequest {

    private Long userId;

    private String userPid;

    private String memberPid;

    private String role = "member"; // LEADER, MEMBER
}
