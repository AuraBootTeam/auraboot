package com.auraboot.framework.agent.runtime.context;

import java.util.List;

/**
 * Ordered context blocks for one agent turn.
 */
public record AgentContextBundle(List<AgentContextBlock> blocks) {

    public AgentContextBundle {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public String renderPromptSection() {
        if (blocks.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (AgentContextBlock block : blocks) {
            if (block == null) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append("\n\n");
            }
            out.append(block.render());
        }
        return out.toString();
    }
}
