package com.auraboot.framework.view.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Link metadata only; never includes the stored password hash. */
public record ViewShareResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String token,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String shareUrl,
        @Schema(type = "string", format = "date-time", nullable = true) String expiresAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean passwordProtected) {
}
