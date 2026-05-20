package com.auraboot.framework.agent.runtime.policy;

import com.auraboot.framework.agent.runtime.context.AgentContextBlock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ToolPolicyCall(String toolName, Map<String, Object> args, List<AgentContextBlock> contextBlocks) {
    public ToolPolicyCall(String toolName, Map<String, Object> args) {
        this(toolName, args, List.of());
    }

    public ToolPolicyCall {
        args = args == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(args));
        contextBlocks = contextBlocks == null ? List.of() : List.copyOf(contextBlocks);
    }
}
