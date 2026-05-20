package com.auraboot.framework.agent.runtime;

import java.util.List;

/**
 * Pure transition contract for agent runtime state.
 */
public interface AgentReducer {

    Result reduce(AgentExecutionState state, AgentRuntimeEvent event);

    record Result(AgentExecutionState state, List<AgentRuntimeEffect> effects) {
        public Result {
            effects = effects == null ? List.of() : List.copyOf(effects);
        }
    }
}
