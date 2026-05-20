package com.auraboot.framework.agent.runtime.policy;

import java.util.LinkedHashMap;
import java.util.Map;

public record ToolPolicyCall(String toolName, Map<String, Object> args) {
    public ToolPolicyCall {
        args = args == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(args));
    }
}
