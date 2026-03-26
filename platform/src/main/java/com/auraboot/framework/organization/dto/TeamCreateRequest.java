package com.auraboot.framework.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class TeamCreateRequest {

    @NotBlank(message = "Team code is required")
    @Size(max = 100, message = "Team code must be at most 100 characters")
    private String code;

    @NotBlank(message = "Team name is required")
    @Size(max = 200, message = "Team name must be at most 200 characters")
    private String name;

    private String description;

    private String leaderId; // user PID
}
