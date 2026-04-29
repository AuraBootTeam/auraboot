package com.auraboot.framework.agent.service;

/**
 * Sealed outcome returned by {@link AgentRunService#executeTaskSync}. Bridges
 * the ACP runtime's {@code @Async} task-driven execution model to a synchronous
 * caller (e.g. {@code ConversationTurnService.runTurn} per design v3.3 §3.5
 * input-adapter mapping). Mirrors {@code TurnOutcome} variants so C.3c can map
 * {@code RunOutcome -> TurnOutcome} without losing information.
 *
 * <p>Phase C.3a contract (Q-C3.2=β "executeTaskSync extraction"):
 * <ul>
 *     <li>{@link Success} — run reached a terminal completed state with a
 *         user-facing final response. Tokens / cost surface so the chokepoint
 *         can attach them to its outbound metric / memory rows.</li>
 *     <li>{@link PendingApproval} — run was suspended waiting on
 *         {@code AgentApprovalGateService}; the caller must persist enough
 *         state for the approve / reject endpoint to resume it later.</li>
 *     <li>{@link Failed} — terminal failure (LLM error, timeout, validation,
 *         no provider configured, agent definition missing, …). The caller
 *         must surface an error to the user; no resume.</li>
 *     <li>{@link Skipped} — pre-execution gate prevented the run from starting
 *         (agent runtime disabled by config, or concurrency cap reached and
 *         the task was queued). No {@code runPid} unless a row was actually
 *         written first; treat as soft-failure at the chokepoint.</li>
 * </ul>
 *
 * <p>The {@code @Async} wrappers ({@code executeTask},
 * {@code executeTaskWithResume}) discard the return value to preserve the
 * fire-and-forget semantics that IM event listeners and the scheduler rely on.
 */
public sealed interface RunOutcome
        permits RunOutcome.Success,
                RunOutcome.PendingApproval,
                RunOutcome.Failed,
                RunOutcome.Skipped {

    /**
     * The {@code ab_agent_run.pid} this outcome corresponds to, or {@code null}
     * for {@link Skipped} cases where no run row was created.
     */
    String runPid();

    record Success(
            String runPid,
            String finalResponse,
            int inputTokens,
            int outputTokens,
            double totalCost
    ) implements RunOutcome {}

    record PendingApproval(String runPid, String message) implements RunOutcome {}

    record Failed(String runPid, String errorMessage) implements RunOutcome {}

    record Skipped(String reason) implements RunOutcome {
        @Override
        public String runPid() {
            return null;
        }
    }
}
