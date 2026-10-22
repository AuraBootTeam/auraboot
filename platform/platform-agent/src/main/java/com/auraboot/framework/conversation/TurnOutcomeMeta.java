package com.auraboot.framework.conversation;

/**
 * Well-known keys carried in {@link TurnOutcome.Success#meta()}.
 *
 * <p>The meta map is deliberately open (engines may add their own), but keys
 * that cross an engine/listener boundary must be declared here — a literal
 * typed on both sides is a silent-drift bug waiting to happen.
 */
public final class TurnOutcomeMeta {

    /**
     * F7 (execution-architecture review, 2026-07-20): {@code Boolean.TRUE} when
     * at least one tool call FAILED during this turn. The model still answers —
     * usually with guidance, guesses or workarounds — but that answer is not an
     * established fact, so {@link TurnCompletionMemoryListener} refuses to
     * persist it.
     *
     * <p>Why this matters: without it, a hallucinated answer produced after a
     * tool failure was written back as an L1 memory and then pre-recalled into
     * every later turn, where it was repeated verbatim across sessions — strong
     * enough to override the prompt-level guardrail. A wrong answer that
     * launders itself into memory stops being a one-off and becomes the
     * assistant's belief.
     */
    public static final String TOOL_FAILURE = "toolFailure";

    private TurnOutcomeMeta() {
    }
}
