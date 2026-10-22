package com.auraboot.framework.agent.service;

/**
 * ThreadLocal carrier for the current step index within a Run, plus optional
 * parallel-group coordinates for ACP P0-5 Parallel Tool Calls.
 *
 * <p>Set by StepLoopService immediately before dispatching each step's tool calls,
 * cleared in a finally block. Read by ActionRecorder so that every Action row
 * points back to its originating step (spec §1: "step_index ↔ execution_plan[i]
 * 一一对应").
 *
 * <p>P0-5 additions: {@link #parallelGroupId} + {@link #parallelIndex} are set
 * inside each parallel-tool worker lambda BEFORE {@code executeToolCall} runs,
 * and cleared in a finally. They flow into {@code ab_agent_action.parallel_group_id}
 * and {@code parallel_index} via {@link ActionRecorder} so we can audit which
 * Actions belonged to the same LLM-emitted parallel batch.
 *
 * <p>Note on async propagation: {@code TenantAwareTaskDecorator} (in
 * {@code event/config}) only captures MetaContext (tenant/user). {@link StepContext}
 * is intentionally <b>not</b> auto-propagated — callers spawning async tool work
 * must set/clear values explicitly inside the worker lambda. This keeps the
 * ThreadLocal single-purpose and avoids leaking step state across unrelated
 * background jobs that share the same async executor.
 *
     * <p>Using ThreadLocal instead of threading the index through every
     * {@code ToolLoopService.executeToolCall} caller keeps the canonical runtime
     * entry stable for callers that don't care about Step structure.
 */
public final class StepContext {

    private static final ThreadLocal<Integer> CURRENT_STEP_INDEX = new ThreadLocal<>();
    private static final ThreadLocal<String> PARALLEL_GROUP_ID = new ThreadLocal<>();
    private static final ThreadLocal<Integer> PARALLEL_INDEX = new ThreadLocal<>();
    /**
     * Current {@code ab_agent_run.pid} bound to the executing thread while a
     * tool call is running. Set by {@code ToolLoopService.executeToolCall}
     * before dispatching to the {@code ToolProviderRegistry}, cleared in a
     * finally so platform tools that need to know "which run am I executing
     * inside" (e.g. {@code platform.delegate_task} which spawns a child run
     * under the current run) can read it without changing the
     * {@code ToolProvider.execute} signature.
     */
    private static final ThreadLocal<String> CURRENT_RUN_PID = new ThreadLocal<>();
    /**
     * Which agent is executing. Kept beside the run pid because the audit needs
     * to separate what carried an action out from whose authority it spent, and
     * the alternative was threading the code through three recorder signatures
     * and every one of their callers.
     */
    private static final ThreadLocal<String> CURRENT_AGENT_CODE = new ThreadLocal<>();
    /**
     * What the run was opened to do, as grounded from the first message.
     *
     * <p>Kept because the intent frame is recomputed every turn, so by the time a
     * later step runs there is nothing left to compare it against — the question
     * "is this still the job we were asked to do" had no way to be asked.
     */
    private static final ThreadLocal<String> OPENING_INTENT = new ThreadLocal<>();
    /**
     * The capability ceiling in force for this turn, as the envelope planner
     * resolved it. Carried so the authorization record can be computed against
     * the same constraint the tool loop is already enforcing, instead of the
     * two agreeing only by coincidence.
     */
    private static final ThreadLocal<String> CAPABILITY_CEILING = new ThreadLocal<>();

    private StepContext() {}

    public static void setStepIndex(int stepIndex) {
        CURRENT_STEP_INDEX.set(stepIndex);
    }

    /** Returns the current step index, or null if not inside a step loop. */
    public static Integer getStepIndex() {
        return CURRENT_STEP_INDEX.get();
    }

    /**
     * Bind parallel-group coordinates for the current thread. Must be called
     * inside the worker lambda before {@code executeToolCall}, and paired
     * with {@link #clearParallel()} in a finally.
     */
    public static void setParallel(String groupId, int index) {
        PARALLEL_GROUP_ID.set(groupId);
        PARALLEL_INDEX.set(index);
    }

    /** Returns the current parallel group id, or null when running serial. */
    public static String getParallelGroupId() {
        return PARALLEL_GROUP_ID.get();
    }

    /** Returns the current parallel index (0-based), or null when running serial. */
    public static Integer getParallelIndex() {
        return PARALLEL_INDEX.get();
    }

    public static void clearParallel() {
        PARALLEL_GROUP_ID.remove();
        PARALLEL_INDEX.remove();
    }

    /**
     * Bind the current ab_agent_run.pid for the executing thread. Set by
     * {@code ToolLoopService.executeToolCall} before dispatching to the tool
     * provider, cleared in finally. Read by tools that need to know which
     * run they're inside (e.g. {@code platform.delegate_task}).
     */
    public static void setRunPid(String runPid) {
        CURRENT_RUN_PID.set(runPid);
    }

    public static String getRunPid() {
        return CURRENT_RUN_PID.get();
    }

    public static void clearRunPid() {
        CURRENT_RUN_PID.remove();
    }

    public static void setAgentCode(String agentCode) {
        CURRENT_AGENT_CODE.set(agentCode);
    }

    public static String getAgentCode() {
        return CURRENT_AGENT_CODE.get();
    }

    public static void clearAgentCode() {
        CURRENT_AGENT_CODE.remove();
    }

    public static void setOpeningIntent(String intent) {
        OPENING_INTENT.set(intent);
    }

    public static String getOpeningIntent() {
        return OPENING_INTENT.get();
    }

    public static void clearOpeningIntent() {
        OPENING_INTENT.remove();
    }

    public static void setCapabilityCeiling(String ceiling) {
        CAPABILITY_CEILING.set(ceiling);
    }

    public static String getCapabilityCeiling() {
        return CAPABILITY_CEILING.get();
    }

    public static void clearCapabilityCeiling() {
        CAPABILITY_CEILING.remove();
    }

    public static void clear() {
        CURRENT_AGENT_CODE.remove();
        OPENING_INTENT.remove();
        CAPABILITY_CEILING.remove();
        CURRENT_STEP_INDEX.remove();
        PARALLEL_GROUP_ID.remove();
        PARALLEL_INDEX.remove();
        CURRENT_RUN_PID.remove();
    }
}
