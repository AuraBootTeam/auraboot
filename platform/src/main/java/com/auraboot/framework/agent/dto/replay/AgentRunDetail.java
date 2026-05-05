package com.auraboot.framework.agent.dto.replay;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Replay UI MVP — composite payload returned by
 * {@code GET /api/admin/agent-runs/{runId}}.
 *
 * <p>Aggregates everything an operator needs to understand a single run
 * without secondary requests:
 * <ul>
 *   <li>{@link #run} — header metadata for the run itself.</li>
 *   <li>{@link #actions} — every {@code ab_agent_action} row sorted by
 *       {@code executed_at ASC} so the UI can render a chronological
 *       timeline (parallel actions surface their {@code parallelGroupId}
 *       so the timeline can group them visually).</li>
 *   <li>{@link #interruptLog} — zero-or-more {@code ab_agent_interrupt_log}
 *       rows whose {@code active_run_id} or {@code subtask_run_id} match
 *       this run.</li>
 *   <li>{@link #childRuns} — runs whose {@code parent_run_id} equals this
 *       run's pid, letting the drawer render the spawn tree.</li>
 *   <li>{@link #bif} — upstream grounding frame; {@code null} when the run
 *       has no BIF (legacy or non-grounded path).</li>
 * </ul>
 */
@Data
@Builder
public class AgentRunDetail {

    private AgentRunListItem run;
    private List<AgentActionItem> actions;
    private List<AgentInterruptItem> interruptLog;
    private List<AgentRunListItem> childRuns;
    private AgentBifSummary bif;
}
