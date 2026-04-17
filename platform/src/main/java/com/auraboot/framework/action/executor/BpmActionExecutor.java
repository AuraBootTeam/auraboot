package com.auraboot.framework.action.executor;

import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * Temporary stub during BpmEngine deletion. Real implementation lands in Task 6.
 */
@Component
public class BpmActionExecutor {
    public boolean supports(String executionMode) {
        return "bpm".equalsIgnoreCase(executionMode);
    }
    public Object execute(Map<String, Object> actionDef, Map<String, Object> record) {
        throw new UnsupportedOperationException("Refactoring in progress, see Task 6");
    }
}
