package com.auraboot.framework.permission.capability;

import java.util.List;

/** A computed role-local change; it does not claim the user's effective access or grant origin. */
public record CapabilitySelectionPreview(
        List<String> grantedCodes,
        List<String> revokedCodes,
        List<String> preservedCodes,
        List<Capability> resultingCapabilities,
        List<String> relatedMenus) {}
