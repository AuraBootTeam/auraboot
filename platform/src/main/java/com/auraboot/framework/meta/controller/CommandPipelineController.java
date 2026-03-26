package com.auraboot.framework.meta.controller;

import com.auraboot.framework.base.service.impl.CommandPipelineRegistry;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API for querying the command pipeline structure and phase handlers.
 * Provides introspection endpoints for IDE tooling, debugging, and admin UIs.
 */
@RestController
@RequestMapping("/api/meta")
@RequiredArgsConstructor
public class CommandPipelineController {

    private final CommandPipelineRegistry pipelineRegistry;

    /**
     * Returns all command pipeline phase definitions with their registered handlers.
     *
     * @return list of phase definitions with handler details
     */
    @GetMapping("/command-phases")
    public ApiResponse<List<Map<String, Object>>> getAllPhases() {
        return ApiResponse.success(pipelineRegistry.exportPipeline());
    }

    /**
     * Returns the phase definition and handlers for a specific stage.
     *
     * @param stage the stage number (1-24)
     * @return phase definition with handlers, or error if stage not found
     */
    @GetMapping("/command-phases/{stage}")
    public ApiResponse<?> getPhase(@PathVariable int stage) {
        return pipelineRegistry.getPhase(stage)
                .map(phase -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("stage", phase.stage());
                    result.put("name", phase.name());
                    result.put("description", phase.description());
                    result.put("interruptible", phase.interruptible());
                    result.put("transaction", phase.transaction().name());

                    List<Map<String, Object>> handlers = pipelineRegistry.getHandlersAtStage(stage).stream()
                            .map(h -> {
                                Map<String, Object> hMap = new LinkedHashMap<>();
                                hMap.put("beanName", h.beanName());
                                hMap.put("className", h.className());
                                hMap.put("interruptible", h.interruptible());
                                hMap.put("transaction", h.transaction().name());
                                hMap.put("description", h.description());
                                return hMap;
                            })
                            .toList();
                    result.put("handlers", handlers);
                    result.put("handlerCount", handlers.size());

                    return ApiResponse.success(result);
                })
                .orElseGet(() -> ApiResponse.error("Phase not found: " + stage));
    }

    /**
     * Returns summary statistics about the pipeline.
     *
     * @return pipeline summary (total phases, annotated stages, total handlers)
     */
    @GetMapping("/command-phases/summary")
    public ApiResponse<Map<String, Object>> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalPhases", pipelineRegistry.getPhaseCount());
        summary.put("annotatedStages", pipelineRegistry.getAnnotatedStageCount());
        summary.put("totalHandlers", pipelineRegistry.getAllHandlers().size());
        summary.put("transactionalStages", 20);
        summary.put("afterCommitStages", 4);
        return ApiResponse.success(summary);
    }
}
