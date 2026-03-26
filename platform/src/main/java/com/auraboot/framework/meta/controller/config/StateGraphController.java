package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.StateGraphCreateRequest;
import com.auraboot.framework.meta.dto.StateTransitionDTO;
import com.auraboot.framework.meta.entity.StateGraphDefinition;
import com.auraboot.framework.meta.service.StateGraphService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * State Graph Controller.
 * CRUD, publish, and visualization APIs for state graph definitions.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/state-graphs")
@RequiredArgsConstructor
public class StateGraphController {

    private final StateGraphService stateGraphService;

    /**
     * Create a new state graph definition.
     */
    @PostMapping
    @RequirePermission(MetaPermission.STATE_GRAPH_MANAGE)
    public ApiResponse<StateGraphDefinition> create(@Valid @RequestBody StateGraphCreateRequest request) {
        StateGraphDefinition definition = stateGraphService.create(request);
        return ApiResponse.success(definition);
    }

    /**
     * Get state graph by pid.
     */
    @GetMapping("/{pid}")
    @RequirePermission(MetaPermission.STATE_GRAPH_READ)
    public ApiResponse<StateGraphDefinition> getByPid(@PathVariable String pid) {
        StateGraphDefinition definition = stateGraphService.getByPid(pid);
        return ApiResponse.success(definition);
    }

    /**
     * Get current state graph by code.
     */
    @GetMapping("/code/{code}")
    @RequirePermission(MetaPermission.STATE_GRAPH_READ)
    public ApiResponse<StateGraphDefinition> getByCode(@PathVariable String code) {
        StateGraphDefinition definition = stateGraphService.getCurrentByCode(code);
        return ApiResponse.success(definition);
    }

    /**
     * List state graphs by model code.
     */
    @GetMapping("/model/{modelCode}")
    @RequirePermission(MetaPermission.STATE_GRAPH_READ)
    public ApiResponse<List<StateGraphDefinition>> listByModelCode(@PathVariable String modelCode) {
        List<StateGraphDefinition> definitions = stateGraphService.listByModelCode(modelCode);
        return ApiResponse.success(definitions);
    }

    /**
     * Update state graph definition (DRAFT only).
     */
    @PutMapping("/{pid}")
    @RequirePermission(MetaPermission.STATE_GRAPH_MANAGE)
    public ApiResponse<StateGraphDefinition> update(@PathVariable String pid,
                                                     @Valid @RequestBody StateGraphCreateRequest request) {
        StateGraphDefinition definition = stateGraphService.update(pid, request);
        return ApiResponse.success(definition);
    }

    /**
     * Publish state graph definition.
     */
    @PostMapping("/{pid}/publish")
    @RequirePermission(MetaPermission.STATE_GRAPH_MANAGE)
    public ApiResponse<Void> publish(@PathVariable String pid) {
        stateGraphService.publish(pid);
        return ApiResponse.success(null);
    }

    /**
     * Delete state graph definition (soft delete).
     */
    @DeleteMapping("/{pid}")
    @RequirePermission(MetaPermission.STATE_GRAPH_MANAGE)
    public ApiResponse<Void> delete(@PathVariable String pid) {
        stateGraphService.delete(pid);
        return ApiResponse.success(null);
    }

    /**
     * Get graph visualization structure ({nodes, edges}) for frontend rendering.
     */
    @GetMapping("/code/{code}/visualization")
    @RequirePermission(MetaPermission.STATE_GRAPH_READ)
    public ApiResponse<Map<String, Object>> getVisualization(@PathVariable String code) {
        Map<String, Object> visualization = stateGraphService.getGraphVisualization(code);
        return ApiResponse.success(visualization);
    }

    /**
     * Get allowed transitions from a given state.
     */
    @GetMapping("/code/{code}/transitions")
    @RequirePermission(MetaPermission.STATE_GRAPH_READ)
    public ApiResponse<List<StateTransitionDTO>> getTransitions(@PathVariable String code,
                                                                 @RequestParam String currentState) {
        List<StateTransitionDTO> transitions = stateGraphService.getTransitionsFromState(code, currentState);
        return ApiResponse.success(transitions);
    }
}
