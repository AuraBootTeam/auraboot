package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;

/**
 * ThreadLocal carrier for the current turn's BusinessIntentFrame.
 *
 * Set by AuraBotChatService immediately after D1 Grounding, cleared when
 * the chat turn ends. Read by ToolLoopService (and other tool-execution
 * code paths) to enforce BIF-derived governance — notably, risk-based
 * automatic Approval Gate routing (riskLevel ≥ L3 → force approval for
 * write tools regardless of per-tool requiresApproval flag).
 *
 * See ACP-Target-vs-Hermes §四 "Risk → Approval" closed loop.
 */
public final class BifContext {

    private static final ThreadLocal<BusinessIntentFrame> CURRENT_BIF = new ThreadLocal<>();

    private BifContext() {}

    public static void setCurrentBif(BusinessIntentFrame bif) {
        CURRENT_BIF.set(bif);
    }

    /** Returns the current-turn BIF, or null if grounding did not run. */
    public static BusinessIntentFrame getCurrentBif() {
        return CURRENT_BIF.get();
    }

    public static void clear() {
        CURRENT_BIF.remove();
    }
}
