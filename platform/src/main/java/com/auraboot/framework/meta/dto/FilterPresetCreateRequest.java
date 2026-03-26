package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for creating/updating a filter preset.
 *
 * @since 3.4.0
 */
@Data
public class FilterPresetCreateRequest {

    @NotBlank
    private String pageCode;

    @NotBlank
    private String modelCode;

    @NotBlank
    private String name;

    /** JSON array of filter conditions. */
    @NotBlank
    private String conditions;

    /** Logic operator: AND / OR. Default AND. */
    private String logic = "and";

    /** Whether this preset is the default for the page. */
    private boolean isDefault = false;

    /** Scope: global (all users) or personal (current user only). */
    private String scope = "personal";
}
