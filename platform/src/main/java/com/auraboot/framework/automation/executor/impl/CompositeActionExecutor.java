package com.auraboot.framework.automation.executor.impl;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.executor.ActionExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Composite action executor that delegates to specific executors based on action type
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Component
public class CompositeActionExecutor implements ActionExecutor {

    private final List<ActionExecutor> executors;

    @Autowired
    public CompositeActionExecutor(List<ActionExecutor> executors) {
        // Filter out self to avoid circular reference
        this.executors = executors.stream()
                .filter(e -> !(e instanceof CompositeActionExecutor))
                .toList();
        log.info("Registered {} action executors", this.executors.size());
    }

    @Override
    public Object execute(AutomationAction action, Map<String, Object> context) {
        String actionType = action.getType();

        for (ActionExecutor executor : executors) {
            if (executor.supports(actionType)) {
                log.debug("Executing action type {} with executor {}",
                        actionType, executor.getClass().getSimpleName());
                return executor.execute(action, context);
            }
        }

        throw new UnsupportedOperationException("No executor found for action type: " + actionType);
    }

    @Override
    public boolean supports(String actionType) {
        return executors.stream().anyMatch(e -> e.supports(actionType));
    }
}
