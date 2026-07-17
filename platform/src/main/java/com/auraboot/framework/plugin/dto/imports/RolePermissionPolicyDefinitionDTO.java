package com.auraboot.framework.plugin.dto.imports;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Seed contract for role-permission policy values stored on ab_role_permission.conditions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RolePermissionPolicyDefinitionDTO {

    /**
     * Permission code whose role binding receives the policy conditions.
     */
    private String permissionCode;

    /**
     * Policy values / Rule Center ABAC binding stored in ab_role_permission.conditions.
     */
    private Map<String, Object> conditions;

    public boolean isValid() {
        return permissionCode != null && !permissionCode.isBlank()
                && conditions != null && !conditions.isEmpty();
    }
}
