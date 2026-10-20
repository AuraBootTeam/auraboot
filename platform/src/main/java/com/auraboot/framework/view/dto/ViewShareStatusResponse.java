package com.auraboot.framework.view.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Inactive or absent links do not imply that any business records are public. */
public record ViewShareStatusResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean shared,
        @Schema(nullable = true) String token,
        @Schema(type = "string", format = "date-time", nullable = true) String expiresAt,
        @Schema(nullable = true) Boolean passwordProtected) {
}
