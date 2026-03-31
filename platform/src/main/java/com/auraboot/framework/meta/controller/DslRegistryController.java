package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.registry.DslRegistryExporter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST endpoint exposing the full DSL registry for tooling, agents, and documentation.
 */
@RestController
@RequestMapping("/api/dsl")
@RequiredArgsConstructor
@Tag(name = "DSL Registry", description = "DSL capability registry export")
public class DslRegistryController {

    private final DslRegistryExporter exporter;

    @GetMapping("/registry")
    @Operation(summary = "Export full DSL registry", description = "Returns all closed enums, open extension registries, and default mappings")
    public ApiResponse<Map<String, Object>> getRegistry() {
        return ApiResponse.ok(exporter.export());
    }
}
