package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.StateTransitionDTO;

import java.util.List;
import java.util.Map;

/**
 * State Transition Engine.
 * Validates state transitions and evaluates guard expressions.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
public interface StateTransitionEngine {

    /**
     * Validate if a transition from currentState triggered by commandCode is allowed.
     * Silently passes if no state graph is bound to the model.
     *
     * @throws com.auraboot.framework.exception.ValidationException if transition is denied
     */
    void validateTransition(Long tenantId, String modelCode, String stateField,
                            String currentState, String commandCode, Map<String, Object> payload);

    /**
     * Get allowed transitions from a given state.
     * Returns empty list if no state graph is bound.
     */
    List<StateTransitionDTO> getAllowedTransitions(Long tenantId, String modelCode, String currentState);

    /**
     * Resolve the target state for a command trigger.
     * Returns null if no state graph bound or no matching transition found.
     */
    String resolveTargetState(Long tenantId, String modelCode, String currentState, String commandCode);
}
