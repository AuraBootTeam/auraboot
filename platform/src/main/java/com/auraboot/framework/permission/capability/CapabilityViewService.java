package com.auraboot.framework.permission.capability;

import java.util.List;

/** Assembles the permission v2 capability view for a role from live permission + grant data. */
public interface CapabilityViewService {

    /** Capability groups for a role, each capability marked granted per the role's current grants. */
    List<CapabilityGroup> resolveForRole(Long roleId);
}
