package com.auraboot.framework.permission.capability;

import java.util.List;
import java.util.Set;

/** Assembles the permission v2 capability view for a role from live permission + grant data. */
public interface CapabilityViewService {

    /** Capability groups for a role, each capability marked granted per the role's current grants. */
    List<CapabilityGroup> resolveForRole(Long roleId);

    /**
     * Apply a capability selection to a role: grant the included codes of selected capabilities and
     * revoke included codes (within the capability universe) that are no longer selected. Permission
     * codes outside any capability's includes (convention-derived / platform) are left untouched.
     */
    void applyCapabilitySelection(Long roleId, Set<String> selectedCapabilityCodes);
}
