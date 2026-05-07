package com.auraboot.framework.automation.event;

import com.auraboot.framework.agent.dto.LlmChunk;

/**
 * Side-channel event emitted by {@code LlmCallExecutor} for every
 * {@link LlmChunk} observed on a streaming LLM call (E.1 Phase 1).
 *
 * <p>This is a <em>live observability</em> event only — it is NOT persisted,
 * and it is NOT part of the {@code ${outputVariable}} write path. Per spec
 * Q7, the output variable is set only after full aggregation, so subscribers
 * to this event stream observe partial deltas while the action is still
 * running but cannot influence its result.
 *
 * <p>Per spec Q11, chunks are not persisted. The
 * {@link com.auraboot.framework.automation.event.AutomationRunStreamPublisher}
 * fan-out is bounded — overflow is dropped (Q8) and surfaced as a counter +
 * a {@code droppedCount} field on the SSE {@code done} envelope so the admin
 * UI can warn users that the live trace is incomplete (the final aggregated
 * output remains correct because that path is synchronous).
 *
 * @param runPid     automation run public id (matches {@code ab_automation_run.pid})
 * @param nodeId     workflow node identifier (action.id) — null for legacy actions
 *                   that do not carry a stable id
 * @param chunk      the chunk just observed
 * @param chunkSeq   monotonic per-(runPid,nodeId) chunk sequence; mirrors
 *                   {@link LlmChunk#seq()} but is materialised here so
 *                   subscribers do not need to peek into the chunk record
 */
public record AutomationLlmChunkEvent(
        String runPid,
        String nodeId,
        LlmChunk chunk,
        long chunkSeq) {
}
