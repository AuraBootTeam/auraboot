package com.auraboot.framework.organization.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TeamUpdateRequest {

    @Size(max = 200, message = "Team name must be at most 200 characters")
    private String name;

    private String description;

    private String leaderId; // user PID

    private String status; // ACTIVE, INACTIVE
}
