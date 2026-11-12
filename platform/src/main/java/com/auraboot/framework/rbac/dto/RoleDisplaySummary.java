package com.auraboot.framework.rbac.dto;
import lombok.Data;
/** Tenant-scoped presentation metadata, never an authorization decision. */
@Data
public class RoleDisplaySummary {
    private Long roleId;
    private Long memberCount;
    private Boolean managementRole;
}
