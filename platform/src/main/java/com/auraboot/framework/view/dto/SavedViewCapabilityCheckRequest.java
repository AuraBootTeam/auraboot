package com.auraboot.framework.view.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.auraboot.framework.view.entity.ViewConfig;
import lombok.Data;

@Data
public class SavedViewCapabilityCheckRequest {
    @Schema(nullable = true)
    private String modelCode;
    @Schema(nullable = true)
    private String pageKey;
    @Schema(nullable = true)
    private String viewType;
    @Schema(nullable = true)
    private ViewConfig viewConfig;
}
