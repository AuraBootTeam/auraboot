package com.auraboot.framework.view.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.auraboot.framework.view.entity.ViewConfig;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request DTO for copying an accessible SavedView into personal scope.
 */
@Data
public class CopySavedViewToPersonalRequest {

    @Size(max = 100, message = "View name must be less than 100 characters")
    @Schema(nullable = true)
    private String name;

    /**
     * Optional config override. When present, the copy uses this config instead
     * of the source view's config.
     */
    @Schema(nullable = true)
    private ViewConfig viewConfig;
}
