package com.auraboot.framework.dsl.compiler.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.dsl.compiler.DslCompilerRegistry;
import com.auraboot.framework.dsl.compiler.model.CompiledPlan;
import com.auraboot.framework.dsl.compiler.model.DslDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for the DSL compilation subsystem.
 */
@Slf4j
@RestController
@RequestMapping("/api/dsl/compile")
@RequiredArgsConstructor
public class DslCompilerController {

    private final DslCompilerRegistry registry;

    /**
     * Compile a DSL definition into an optimized execution plan.
     *
     * @param definition the DSL definition payload
     * @return the compiled plan
     */
    @PostMapping
    public ApiResponse<CompiledPlan> compile(@RequestBody DslDefinition definition) {
        CompiledPlan plan = registry.compile(definition);
        return ApiResponse.success(plan);
    }

    /**
     * Return cache statistics.
     */
    @GetMapping("/cache/stats")
    public ApiResponse<Map<String, Object>> cacheStats() {
        return ApiResponse.success(registry.cacheStats());
    }

    /**
     * Clear the plan cache.
     */
    @DeleteMapping("/cache")
    public ApiResponse<String> clearCache() {
        registry.clearCache();
        return ApiResponse.success("Cache cleared");
    }

    /**
     * List all registered compiler types.
     */
    @GetMapping("/types")
    public ApiResponse<List<String>> registeredTypes() {
        return ApiResponse.success(registry.registeredTypes());
    }
}
