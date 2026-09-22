package com.auraboot.framework.plugin.extension;

import java.util.List;

/** Host-owned, tenant-bound role assignment bridge for trusted command handlers. */
public interface TenantRoleAssignmentAccessor {
    String SETTINGS_KEY = "__tenantRoleAssignmentAccessor";

    List<String> userPidsWithRole(String roleCode);

    void setRole(String userPid, String roleCode, boolean assigned);
}
