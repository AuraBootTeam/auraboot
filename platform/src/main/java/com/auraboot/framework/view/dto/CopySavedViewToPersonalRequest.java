package com.auraboot.framework.view.dto;

import com.auraboot.framework.view.entity.ViewConfig;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request DTO for copying an accessible SavedView into personal scope.
 */
@Data
public class CopySavedViewToPersonalRequest {

    @Size(max = 100, message = "View name must be less than 100 characters")
    private String name;

    /**
     * Optional config override. When present, the copy uses this config instead
     * of the source view's config.
     */
    private ViewConfig viewConfig;
}
