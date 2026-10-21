package com.auraboot.framework.view.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/** Public view configuration. Business records are outside this token's access boundary. */
public record PublicSharedViewResponse(
        @Schema(nullable = true) String name,
        @Schema(nullable = true) String modelCode,
        @Schema(nullable = true) String viewType,
        @Schema(description = "Stored JSON view configuration with private share metadata removed")
        Map<String, Object> viewConfig) {
}
