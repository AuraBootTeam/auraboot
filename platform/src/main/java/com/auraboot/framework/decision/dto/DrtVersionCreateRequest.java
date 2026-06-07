package com.auraboot.framework.decision.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for creating a new draft decision version.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class DrtVersionCreateRequest {

    @NotBlank
    private String kind;

    @NotBlank
    private String runtimeAdapter;

    /** Optional human label */
    private String versionTag;

    /** The serialised decision payload (AST, DMN XML, etc.) */
    private JsonNode contentJson;

    private JsonNode inputSchemaJson;
    private JsonNode outputSchemaJson;
    private JsonNode contextSchemaJson;
}
