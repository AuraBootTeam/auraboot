package com.auraboot.framework.permission.dto;

import com.auraboot.framework.permission.capability.CapabilityGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Public identifiers only; revision covers the underlying grants, conditions and catalog. */
public record RoleAuthorizationDraftSnapshot(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String rolePid,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String defaultScopeType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<CapabilityGroup> groups,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PermissionMatrixDTO matrix,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<DataScopeUpdateRequest> scopes,
        List<RoleFieldPolicyView> fieldPolicies,
        com.auraboot.framework.menu.dto.RoleNavigation.Selection navigation,
        List<RoleFieldAccess.Model> fieldAccess) {
    public RoleAuthorizationDraftSnapshot(String rolePid,String revision,String defaultScopeType,List<CapabilityGroup> groups,PermissionMatrixDTO matrix,List<DataScopeUpdateRequest> scopes,List<RoleFieldPolicyView> fieldPolicies,com.auraboot.framework.menu.dto.RoleNavigation.Selection navigation) {
        this(rolePid,revision,defaultScopeType,groups,matrix,scopes,fieldPolicies,navigation,null);
    }
    public RoleAuthorizationDraftSnapshot(String rolePid,String revision,String defaultScopeType,List<CapabilityGroup> groups,PermissionMatrixDTO matrix,List<DataScopeUpdateRequest> scopes,List<RoleFieldPolicyView> fieldPolicies) {
        this(rolePid,revision,defaultScopeType,groups,matrix,scopes,fieldPolicies,null,null);
    }
}
