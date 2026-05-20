package com.auraboot.framework.agent.runtime.context;

/**
 * One labeled context block prepared for an agent turn.
 */
public record AgentContextBlock(
        String title,
        String body,
        AgentContextProvenance provenance) {

    public AgentContextBlock {
        title = hasText(title) ? title : "Context";
        body = body == null ? "" : body;
        provenance = provenance == null
                ? new AgentContextProvenance(null, null, null, null, null, null, null, null, false)
                : provenance;
    }

    String render() {
        return "## " + title + "\n"
                + "[" + provenance.renderLabel() + "]\n"
                + body;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
