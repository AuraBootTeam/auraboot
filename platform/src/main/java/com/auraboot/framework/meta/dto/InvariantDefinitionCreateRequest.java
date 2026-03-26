package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for creating/updating an invariant definition.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Data
public class InvariantDefinitionCreateRequest {

    @NotBlank
    private String code;

    private String displayName;

    private String description;

    @NotBlank
    private String expression;

    /**
     * PRE / POST / ALWAYS.
     */
    @NotBlank
    private String invariantType;

    /**
     * ERROR / WARN.
     */
    @NotBlank
    private String severity;

    /**
     * MODEL / COMMAND / STATE.
     */
    @NotBlank
    private String scopeType;

    /**
     * Scope reference value (modelCode / commandCode / stateNodeCode).
     */
    private String scopeRef;

    @NotBlank
    private String modelCode;

    private boolean enabled = true;
}
