package com.auraboot.framework.rbac.dto;

import com.auraboot.framework.rbac.entity.Role;
import lombok.Data;

import java.time.Instant;

@Data
public class RoleResponse {
    private String pid;
    private String name;
    private String code;
    private String description;
    private String type;
    private String scopeType;
    private String defaultDataScopeType;
    private Integer priority;
    private String status;
    private Boolean isDefault;
    private Boolean isSystem;
    private Instant createdAt;
    private Instant updatedAt;

    public static RoleResponse from(Role role) {
        if (role == null) {
            return null;
        }
        RoleResponse response = new RoleResponse();
        response.setPid(role.getPid());
        response.setName(role.getName());
        response.setCode(role.getCode());
        response.setDescription(role.getDescription());
        response.setType(role.getType());
        response.setScopeType(role.getScopeType());
        response.setDefaultDataScopeType(role.getDefaultDataScopeType());
        response.setPriority(role.getPriority());
        response.setStatus(role.getStatus());
        response.setIsDefault(role.getIsDefault());
        response.setIsSystem(role.getIsSystem());
        response.setCreatedAt(role.getCreatedAt());
        response.setUpdatedAt(role.getUpdatedAt());
        return response;
    }
}
