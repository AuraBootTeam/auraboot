package com.auraboot.framework.automation.executor;

import com.auraboot.framework.automation.entity.AutomationAction;

import java.util.Map;

/**
 * Interface for executing automation actions
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
public interface ActionExecutor {

    /**
     * Execute an automation action
     *
     * @param action the action to execute
     * @param context execution context (contains record data, previous action results, etc.)
     * @return action result (type depends on action type)
     * @throws ActionExecutionException if execution fails
     */
    Object execute(AutomationAction action, Map<String, Object> context);

    /**
     * Check if this executor supports the given action type
     *
     * @param actionType action type code
     * @return true if supported
     */
    boolean supports(String actionType);
}
