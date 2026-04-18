package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.constant.CommandStage;
import com.auraboot.framework.meta.service.impl.CommandPhaseRegistry;
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

    private final CommandPhaseRegistry pipelineRegistry;

    /**
     * Returns all command pipeline phase definitions with their registered handlers.
     *
     * @return list of phase definitions with handler details
     */
    @GetMapping("/command-phases")
    public ApiResponse<List<Map<String, Object>>> getAllPhases() {
        return ApiResponse.success(
                pipelineRegistry.getAllPhases().stream()
                        .map(this::toPhaseMap)
                        .toList()
        );
    }

    /**
     * Returns the phase definition and handlers for a specific stage.
     *
     * @param stage the stage number (1-24)
     * @return phase definition with handlers, or error if stage not found
     */
    @GetMapping("/command-phases/{stage}")
    public ApiResponse<?> getPhase(@PathVariable int stage) {
        return pipelineRegistry.getAllPhases().stream()
                .filter(phase -> phase.stage() == stage)
                .findFirst()
                .map(phase -> {
                    return ApiResponse.success(toPhaseMap(phase));
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
        summary.put("totalPhases", pipelineRegistry.getAllPhases().size());
        summary.put("annotatedStages", pipelineRegistry.getAnnotatedStageCount());
        summary.put("totalHandlers", pipelineRegistry.getAllPhases().stream()
                .mapToInt(phase -> phase.handlers().size())
                .sum());
        summary.put("transactionalStages", CommandStage.TOTAL_TRANSACTIONAL_STAGES);
        summary.put("afterCommitStages", CommandStage.GOVERNANCE_SNAPSHOT - CommandStage.TOTAL_TRANSACTIONAL_STAGES);
        return ApiResponse.success(summary);
    }

    private Map<String, Object> toPhaseMap(CommandPhaseRegistry.PhaseDescriptor phase) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stage", phase.stage());
        result.put("name", phase.name());
        result.put("description", CommandStage.descriptionOf(phase.stage()));
        result.put("interruptible", isInterruptible(phase));
        result.put("transaction", phase.transactional() ? "INHERITED" : "NOT_SUPPORTED");

        List<Map<String, Object>> handlers = phase.handlers().stream()
                .map(h -> {
                    Map<String, Object> hMap = new LinkedHashMap<>();
                    hMap.put("beanName", h.beanName());
                    hMap.put("className", h.className());
                    hMap.put("interruptible", h.interruptible());
                    hMap.put("transaction", h.transactional() ? "INHERITED" : "NOT_SUPPORTED");
                    hMap.put("description", h.description());
                    return hMap;
                })
                .toList();
        result.put("handlers", handlers);
        result.put("handlerCount", handlers.size());
        return result;
    }

    private boolean isInterruptible(CommandPhaseRegistry.PhaseDescriptor phase) {
        if (!phase.handlers().isEmpty()) {
            return phase.handlers().stream().allMatch(CommandPhaseRegistry.PhaseHandlerDescriptor::interruptible);
        }
        return switch (phase.stage()) {
            case CommandStage.SCHEMA_VALIDATE,
                    CommandStage.ENTITLEMENT_CHECK,
                    CommandStage.SOD_CHECK,
                    CommandStage.STATE_CHECK,
                    CommandStage.ASSERT,
                    CommandStage.PRE_INVARIANT,
                    CommandStage.CROSS_FIELD_VALIDATION,
                    CommandStage.HANDLER,
                    CommandStage.POST_INVARIANT -> true;
            default -> false;
        };
    }
}
