package com.auraboot.framework.dsl.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.dsl.dto.DslIntrospectionResponse;
import com.auraboot.framework.dsl.dto.DslIntrospectionResponse.CapabilityCatalog;
import com.auraboot.framework.dsl.dto.DslIntrospectionResponse.ModelIntrospection;
import com.auraboot.framework.dsl.service.DslIntrospectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.Set;

/**
 * DSL Schema Introspection Protocol endpoints.
 * <p>
 * Provides structured metadata about the complete DSL surface area,
 * enabling third-party tools and AI agents to auto-discover models,
 * fields, commands, pages, and platform capabilities.
 * </p>
 *
 * @author AuraBoot Team
 * @since 3.0.0
 */
@RestController
@RequestMapping("/api/dsl/introspect")
@RequiredArgsConstructor
@Tag(name = "DSL Introspection", description = "Schema Introspection Protocol for DSL auto-discovery")
public class DslIntrospectionController {

    private final DslIntrospectionService introspectionService;

    /**
     * Return the full DSL schema for the current tenant.
     *
     * @param scope optional comma-separated scopes: models, capabilities.
     *              If omitted, all sections are returned.
     */
    @GetMapping
    @Operation(summary = "Full DSL schema introspection",
            description = "Returns all models with fields, commands, pages, and platform capabilities")
    public ApiResponse<DslIntrospectionResponse> getFullSchema(
            @Parameter(description = "Comma-separated scopes: models, capabilities")
            @RequestParam(value = "scope", required = false) String scope) {

        Set<String> scopes = parseScopes(scope);
        DslIntrospectionResponse response = introspectionService.getFullSchema(scopes);
        return ApiResponse.success(response);
    }

    /**
     * Return the schema for a single model, including its fields, commands, and pages.
     */
    @GetMapping("/models/{modelCode}")
    @Operation(summary = "Single model schema introspection",
            description = "Returns a model with its fields, commands, and pages")
    public ApiResponse<ModelIntrospection> getModelSchema(
            @PathVariable("modelCode") String modelCode) {

        ModelIntrospection model = introspectionService.getModelSchema(modelCode);
        if (model == null) {
            return ApiResponse.error("Model not found: " + modelCode);
        }
        return ApiResponse.success(model);
    }

    /**
     * Return the platform capability catalog (data types, block types, etc.).
     */
    @GetMapping("/capabilities")
    @Operation(summary = "Platform capability catalog",
            description = "Returns all supported DSL capabilities: data types, block types, command types, etc.")
    public ApiResponse<CapabilityCatalog> getCapabilities() {
        CapabilityCatalog capabilities = introspectionService.getAvailableCapabilities();
        return ApiResponse.success(capabilities);
    }

    private Set<String> parseScopes(String scope) {
        if (scope == null || scope.isBlank()) {
            return Set.of();
        }
        Set<String> scopes = new HashSet<>();
        for (String s : scope.split(",")) {
            String trimmed = s.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                scopes.add(trimmed);
            }
        }
        return scopes;
    }
}
