package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Request DTO for creating/updating a State Graph Definition.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Data
public class StateGraphCreateRequest {

    @NotBlank
    private String code;

    private String displayName;

    private String description;

    @NotBlank
    private String modelCode;

    /**
     * Field name in the model that holds the state value (defaults to "status").
     */
    private String stateField;

    @NotNull
    private List<StateNodeDTO> nodes;

    @NotNull
    private List<StateTransitionDTO> transitions;
}
