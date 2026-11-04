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
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<DataScopeUpdateRequest> scopes) {
}
