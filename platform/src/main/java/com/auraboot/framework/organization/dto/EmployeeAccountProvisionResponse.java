package com.auraboot.framework.organization.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Result of opening a login account for an organization employee.
 */
@Data
@Builder
public class EmployeeAccountProvisionResponse {
    private String employeePid;
    private String userPid;
    private String memberPid;
    private String email;
    private String userName;
    private String displayName;
    private boolean createdUser;
    private boolean createdMember;
    private boolean adminManaged;
    private String temporaryPassword;
    private List<String> assignedRoles;
}
