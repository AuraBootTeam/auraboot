package com.auraboot.framework.agent.triage;

/**
 * Triage buckets for the Stage 2.5 Pre-Grounding decision. See
 * enterprise/docs/agent/contracts/pre-grounding-triage.md.
 */
public enum TriageBucket {
    /** Trivial chat / general Q&A, no platform semantics. Bypass D1/Skill/Action/Tool. */
    LIGHT_CHAT,
    /** Explanation-style answer needing page/record context but no platform action. */
    CONTEXTUAL_ANSWER,
    /**
     * Synchronous single write action ("创建一个客户"): executes in the chat
     * runtime with the full tool catalog behind policy gates. Added 2026-07-19
     * (review G3) — these turns previously squatted in {@link #LIGHT_CHAT},
     * whose "no platform semantics" definition made every cross-cutting
     * consumer (memory writeback, eval sampling) skip real platform actions.
     */
    SYNC_ACTION,
    /** Full ACP compilation chain: D1 → BIF → Skill → Action → Tool. */
    ACP_RUN
}
