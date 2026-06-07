package com.auraboot.framework.decision.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * Request body for {@code POST /api/decision/test-run} — evaluates draft content
 * in-memory without persistence.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class DrtTestRunRequest {

    @NotBlank
    private String kind;

    @NotBlank
    private String runtimeAdapter;

    @NotNull
    private JsonNode contentJson;

    /** Context data keyed by Scope name. */
    private Map<String, Map<String, Object>> context;
}
