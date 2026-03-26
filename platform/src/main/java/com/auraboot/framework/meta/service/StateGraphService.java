package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.StateGraphCreateRequest;
import com.auraboot.framework.meta.dto.StateTransitionDTO;
import com.auraboot.framework.meta.entity.StateGraphDefinition;

import java.util.List;
import java.util.Map;

/**
 * State Graph Service.
 * CRUD, publish, and visualization for state graph definitions.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
public interface StateGraphService {

    StateGraphDefinition create(StateGraphCreateRequest request);

    StateGraphDefinition getByPid(String pid);

    StateGraphDefinition getCurrentByCode(String code);

    List<StateGraphDefinition> listByModelCode(String modelCode);

    StateGraphDefinition update(String pid, StateGraphCreateRequest request);

    void publish(String pid);

    void delete(String pid);

    /**
     * Get graph structure for frontend visualization.
     * Returns {nodes: [...], edges: [...]} format.
     */
    Map<String, Object> getGraphVisualization(String code);

    /**
     * Get allowed transitions from a given state.
     */
    List<StateTransitionDTO> getTransitionsFromState(String code, String currentState);
}
