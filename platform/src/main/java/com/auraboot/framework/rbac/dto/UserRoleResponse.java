package com.auraboot.framework.rbac.dto;

import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import lombok.Data;

import java.time.Instant;

@Data
public class UserRoleResponse {
    private String pid;
    private String memberPid;
    private String rolePid;
    private String assignType;
    private Instant effectiveDate;
    private Instant expiryDate;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;

    public static UserRoleResponse from(UserRole userRole, TenantMember member, Role role) {
        if (userRole == null) {
            return null;
        }
        UserRoleResponse response = new UserRoleResponse();
        response.setPid(userRole.getPid());
        response.setMemberPid(member != null ? member.getPid() : null);
        response.setRolePid(role != null ? role.getPid() : null);
        response.setAssignType(userRole.getAssignType());
        response.setEffectiveDate(userRole.getEffectiveDate());
        response.setExpiryDate(userRole.getExpiryDate());
        response.setStatus(userRole.getStatus());
        response.setCreatedAt(userRole.getCreatedAt());
        response.setUpdatedAt(userRole.getUpdatedAt());
        return response;
    }
}
