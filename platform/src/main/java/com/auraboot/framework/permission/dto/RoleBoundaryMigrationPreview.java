package com.auraboot.framework.permission.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/** Role-local migration evidence, never an effective member-access decision or an automatic grant. */
@Schema(requiredProperties = {"rolePid", "revision", "boundaries", "bindings", "scopes", "fieldPolicies",
        "boundPolicies", "unmappedPermissionCodes", "unresolvedBindingPids", "equivalentMigrationProven"})
public record RoleBoundaryMigrationPreview(
        String rolePid, String revision, @Schema(nullable = true) String defaultScopeType,
        List<Boundary> boundaries, List<Binding> bindings, List<Scope> scopes,
        List<RoleFieldPolicyView> fieldPolicies, List<Policy> boundPolicies, List<String> unmappedPermissionCodes,
        List<String> unresolvedBindingPids, boolean equivalentMigrationProven) {
    @Schema(name = "RoleBoundaryMigrationBoundary", requiredProperties = {"commandCode", "rootModel", "targetAction", "operations"})
    public record Boundary(String commandCode, String rootModel, String targetAction, List<Operation> operations) {}

    @Schema(name = "RoleBoundaryMigrationOperation", requiredProperties = {"permissionCode", "scopeOrigin", "scopeStatus", "bindingPids"})
    public record Operation(String permissionCode, @Schema(nullable = true) String permissionPid,
            @Schema(nullable = true) String resourceCode, @Schema(nullable = true) String actionCode,
            @Schema(nullable = true) String currentScopeType, String scopeOrigin, String scopeStatus, List<String> bindingPids) {}

    @Schema(name = "RoleBoundaryMigrationBinding", requiredProperties = {"bindingPid", "sourceOwnership"})
    public record Binding(String bindingPid, @Schema(nullable = true) String permissionPid, @Schema(nullable = true) String permissionCode,
            @Schema(nullable = true) String grantType, @Schema(nullable = true) Integer priority,
            @Schema(nullable = true) LocalDate effectiveDate, @Schema(nullable = true) LocalDate expiryDate,
            @Schema(nullable = true) String status, @Schema(nullable = true) JsonNode conditions,
            @Schema(nullable = true) JsonNode conditionAst, @Schema(nullable = true) JsonNode dataScopeAst,
            @Schema(nullable = true) JsonNode fieldScope, @Schema(nullable = true) String sourceRef,
            @Schema(nullable = true, description = "Owning publish snapshot pointer; never migration authorization") Long snapshotId,
            @Schema(allowableValues = {"DIRECT", "DETACHED_COPY", "PUBLISHED_SNAPSHOT", "EXTERNAL_SOURCE"},
                    description = "Configuration owner classification, not effective access or migration eligibility") String sourceOwnership) {}

    @Schema(name = "RoleBoundaryMigrationPolicy", requiredProperties = {"policyPid"})
    public record Policy(String policyPid, @Schema(nullable = true) String modelCode, @Schema(nullable = true) String policyType,
            @Schema(nullable = true) String scopeType, @Schema(nullable = true) String scopeExpression,
            @Schema(nullable = true) JsonNode conditionAst, @Schema(nullable = true) String fieldCode,
            @Schema(nullable = true) String maskType, @Schema(nullable = true) String maskExpression,
            @Schema(nullable = true) Integer priority, @Schema(nullable = true) Boolean enabled) {}

    @Schema(name = "RoleBoundaryMigrationScope", requiredProperties = {"resourceCode", "actionCode"})
    public record Scope(@Schema(nullable = true) String scopePid, String resourceCode, String actionCode,
            @Schema(nullable = true) String scopeType, @Schema(nullable = true) String mergeStrategy,
            @Schema(nullable = true) JsonNode scopeConfig) {}
}
