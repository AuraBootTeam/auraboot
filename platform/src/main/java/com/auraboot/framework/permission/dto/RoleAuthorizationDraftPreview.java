package com.auraboot.framework.permission.dto;

import com.auraboot.framework.permission.capability.CapabilitySelectionPreview;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Same precondition and validation used by commit; does not modify live authorization. */
public record RoleAuthorizationDraftPreview(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CapabilitySelectionPreview capabilities,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean defaultScopeChanged,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String defaultScopeType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean applyDefaultToCurrentGrants,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<DataScopeUpdateRequest> scopeChanges,
        RoleFieldPolicyPreview fieldPolicies,
        com.auraboot.framework.menu.dto.RoleNavigation.Selection navigation,
        List<RoleFieldAccess.Reviewed> fieldAccessChanges,
        List<RoleGrantFields.Reviewed> grantFieldChanges,
        List<RoleGrantFields.Binding> grantFieldCandidates) {
    public RoleAuthorizationDraftPreview(String revision,CapabilitySelectionPreview capabilities,boolean changed,String scope,boolean apply,List<DataScopeUpdateRequest> scopes,RoleFieldPolicyPreview policies,com.auraboot.framework.menu.dto.RoleNavigation.Selection navigation,List<RoleFieldAccess.Reviewed> fields,List<RoleGrantFields.Reviewed> grantFields) {
        this(revision,capabilities,changed,scope,apply,scopes,policies,navigation,fields,grantFields,null);
    }
    public RoleAuthorizationDraftPreview(String revision,CapabilitySelectionPreview capabilities,boolean changed,String scope,boolean apply,List<DataScopeUpdateRequest> scopes,RoleFieldPolicyPreview policies,com.auraboot.framework.menu.dto.RoleNavigation.Selection navigation,List<RoleFieldAccess.Reviewed> fields) {
        this(revision,capabilities,changed,scope,apply,scopes,policies,navigation,fields,null);
    }
    public RoleAuthorizationDraftPreview(String revision,CapabilitySelectionPreview capabilities,boolean changed,String scope,boolean apply,List<DataScopeUpdateRequest> changes,RoleFieldPolicyPreview fields,com.auraboot.framework.menu.dto.RoleNavigation.Selection navigation){
        this(revision,capabilities,changed,scope,apply,changes,fields,navigation,null);
    }
    public RoleAuthorizationDraftPreview(String revision,CapabilitySelectionPreview capabilities,boolean defaultScopeChanged,String defaultScopeType,boolean applyDefaultToCurrentGrants,List<DataScopeUpdateRequest> scopeChanges,RoleFieldPolicyPreview fieldPolicies) {
        this(revision,capabilities,defaultScopeChanged,defaultScopeType,applyDefaultToCurrentGrants,scopeChanges,fieldPolicies,null,null);
    }
}
