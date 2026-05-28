package com.auraboot.framework.agent.memory.extraction;

import java.util.Map;

/**
 * One signal a run emits that the rule-prefilter can match against. Mirrors
 * the shape of items the {@code AgentRunService.AgentLoopResult} carries:
 * tool calls, response payloads, BPM events.
 *
 * <p>Test-only DTO. The replay tool builds these from historical
 * {@code ab_agent_run + ab_agent_observation} rows.
 */
public record ExtractionSignal(
        String type,            // "tool_call" | "tool_response" | "bpm_event"
        String name,            // tool name / event type
        Map<String, Object> payload  // arbitrary structured data
) {
    public ExtractionSignal {
        if (type == null) type = "";
        if (name == null) name = "";
        if (payload == null) payload = Map.of();
    }
}
