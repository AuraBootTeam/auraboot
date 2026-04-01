package com.auraboot.framework.organization.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request to link an existing tenant member to a new employee record.
 */
@Data
public class LinkMemberRequest {

    @NotBlank(message = "Member PID is required")
    private String memberPid;

    @NotBlank(message = "Department is required")
    private String deptPid;

    private String positionPid;
}
