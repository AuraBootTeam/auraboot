package com.auraboot.framework.view.dto;

import com.auraboot.framework.view.entity.ViewConfig;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for auto-saving view configuration.
 * Backend performs atomic upsert: finds existing implicit view or creates one.
 * Eliminates frontend race conditions from create-if-not-exists logic.
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Data
public class AutoSaveViewRequest {

    @NotBlank(message = "Model code is required")
    private String modelCode;

    private String pageKey;

    private ViewConfig viewConfig;
}
