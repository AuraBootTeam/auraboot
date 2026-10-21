package com.auraboot.framework.rbac.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.auraboot.framework.rbac.entity.Role;
import lombok.Data;

import java.time.Instant;

@Data
public class RoleResponse {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String pid;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    private String code;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    private String description;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    private String type;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    private String scopeType;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    private String defaultDataScopeType;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    private Integer priority;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    private String status;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    private Boolean isDefault;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    private Boolean isSystem;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    private Instant createdAt;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
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
