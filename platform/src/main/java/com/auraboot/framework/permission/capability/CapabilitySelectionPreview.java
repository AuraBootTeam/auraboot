package com.auraboot.framework.permission.capability;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A computed role-local change; it does not claim the user's effective access or grant origin. */
public record CapabilitySelectionPreview(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> grantedCodes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> revokedCodes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> preservedCodes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Capability> resultingCapabilities,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> relatedMenus) {}
