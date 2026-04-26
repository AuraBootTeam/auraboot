package com.auraboot.framework.agent.triage;

/**
 * Three triage buckets for the Stage 2.5 Pre-Grounding decision. See
 * enterprise/docs/agent/contracts/pre-grounding-triage.md.
 */
public enum TriageBucket {
    /** Trivial chat / general Q&A, no platform semantics. Bypass D1/Skill/Action/Tool. */
    LIGHT_CHAT,
    /** Explanation-style answer needing page/record context but no platform action. */
    CONTEXTUAL_ANSWER,
    /** Full ACP compilation chain: D1 → BIF → Skill → Action → Tool. */
    ACP_RUN
}
