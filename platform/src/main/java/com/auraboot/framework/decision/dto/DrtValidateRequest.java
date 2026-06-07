package com.auraboot.framework.decision.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request body for {@code POST /api/decision/validate}.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class DrtValidateRequest {

    @NotBlank
    private String kind;

    @NotBlank
    private String runtimeAdapter;

    @NotNull
    private JsonNode contentJson;
}
