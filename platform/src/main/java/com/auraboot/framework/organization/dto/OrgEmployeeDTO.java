package com.auraboot.framework.organization.dto;

/**
 * Org employee data transfer object combining employee, member, and user data.
 */
public record OrgEmployeeDTO(
    String pid,
    String name,
    String code,
    String email,
    String phone,
    String gender,
    String deptPid,
    String deptName,
    String positionPid,
    String positionName,
    String status,
    String type,
    String memberPid,
    String userPid
) {}
