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
        RoleFieldPolicyPreview fieldPolicies) {
}
