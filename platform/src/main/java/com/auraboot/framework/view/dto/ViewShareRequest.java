package com.auraboot.framework.view.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Optional public-link settings; expiration and password policy remain service-owned. */
public record ViewShareRequest(
        @Schema(nullable = true) String password,
        @Schema(nullable = true) Integer expireHours) {
}
