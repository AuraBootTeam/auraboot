package com.auraboot.framework.agent.port;

import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;

import java.util.Map;

/**
 * Port interface for named-agent (ACP) chat execution.
 *
 * <p>Phase B.0 evolution (2026-04-27): the legacy SSE-bound entry
 * {@code streamAgentChat(emitter)} has been replaced with the chokepoint-aligned
 * {@code runAgentTurn(TurnContext, ChatRequest, ResponseSink): TurnOutcome} so
 * that the named-agent path goes through the same {@link com.auraboot.framework.conversation.ConversationTurnService}
 * lifecycle as the aurabot main path. This eliminates the dual-path scaffold
 * left behind by Phase A.5 and lets Phase B's persistence / event / audit
 * features apply uniformly to both paths through one chokepoint.
 *
 * <p>Resume after confirmation goes through {@code ConversationTurnService.resumeTurn}
 * which dispatches into {@code AuraBotChatService.resumeApprovedTurnFromPending}.
 * That entry consumes the generic {@code ChatSessionStore.PendingTool} state
 * (providerCode / apiKey / model / systemPrompt are stored at suspend time,
 * regardless of which port created them), so the resume path does not need a
 * port-specific override — the previous {@code resumeAgentToolAfterConfirmation}
 * default method introduced on main has been collapsed into the chokepoint.
 *
 * <p>{@code AgentChatPortImpl} is the primary implementation. When unavailable
 * (bean not registered in the current runtime), {@code ConversationTurnServiceImpl}
 * surfaces a {@link TurnOutcome.Failed} via the sink — same observability surface
 * as any other failure outcome.
 */
public interface AgentChatPort {

    /**
     * Check whether an ACP Agent with the given code exists for the tenant.
     *
     * @param tenantId  current tenant ID
     * @param agentCode agent code to look up
     * @return true if the agent exists and is active
     */
    boolean agentExists(Long tenantId, String agentCode);

    /**
     * Resolve the display name for an agent (used in the "chatting with" header).
     *
     * @param tenantId  current tenant ID
     * @param agentCode agent code
     * @return agent display name, or the agentCode if not found
     */
    String resolveAgentName(Long tenantId, String agentCode);

    /**
     * Phase B.0 sync entry. Mirrors {@link com.auraboot.framework.aurabot.service.AuraBotChatService#executeAuraBotTurn}
     * for the named-agent path.
     *
     * <p>The implementation should:
     * <ol>
     *     <li>Load the agent definition (system prompt, provider, model) for the
     *         {@code agentCode} carried by {@code request}</li>
     *     <li>Build LLM messages from history + current message</li>
     *     <li>Resolve and bind agent tools</li>
     *     <li>Run the tool loop, writing chunks / tool events through {@code sink}</li>
     *     <li>Return a {@link TurnOutcome} reflecting actual completion
     *         ({@link TurnOutcome.Success} on normal end,
     *         {@link TurnOutcome.Failed} on any error path)</li>
     * </ol>
     *
     * <p>The implementation MUST NOT manage the SSE emitter lifecycle directly
     * (write through {@code sink} only) and MUST NOT spawn its own async worker
     * (the caller — {@code ConversationTurnServiceImpl.runTurn} — is already on
     * the controller's worker thread per Q-A.4=A').
     *
     * @param ctx     materialized turn context (turnId, tenantId, userId, agentId, etc.)
     * @param request the original {@link ChatRequest} (message, history, pageContext,
     *                options, agentCode); equivalent to {@code TurnRequest.legacyRequest()}
     * @param sink    transport-agnostic response sink (typically {@code SseResponseSink})
     * @return the outcome reflecting how the turn ended
     */
    default TurnOutcome runAgentTurn(TurnContext ctx, ChatRequest request, ResponseSink sink) {
        return runAgentTurn(ctx, request, sink, null);
    }

    /**
     * Phase D.3-chokepoint DC.3a (Q-DC.1=A' lock) — server-only overrides
     * variant. Trusted internal callers (e.g. {@code AgentReplyTask} for
     * group-chat) build a context bag and pass it to override
     * {@code AgentChatPortImpl}'s default per-step build path.
     *
     * <p>{@code overrides=null} means "behave exactly as the 3-arg overload"
     * — the aurabot main path uses this default. When non-null, the implementation
     * MUST honour each non-null field on {@link AgentTurnOverrides} (system
     * prompt, messages, tool definitions, extra tools, persistence flag).
     *
     * <p><b>Security boundary</b>: {@link AgentTurnOverrides} is a server-only
     * object, NEVER deserialised from a client request. The
     * {@code @RequestBody} controllers ({@code AuraBotController}) always pass
     * {@code null} here. See design v5 §10.7 Fix 1 for the prompt-injection /
     * tool-forgery threat model that motivated splitting overrides off the
     * public {@code ChatRequest} DTO.
     *
     * <p><b>Replaces DC.1 4-arg signature</b>: the previous
     * {@code runAgentTurn(ctx, request, sink, List<ToolDefinition> extraTools)}
     * overload (DC.1) is removed; its single {@code extraTools} parameter
     * is now {@link AgentTurnOverrides#extraTools()}.
     */
    TurnOutcome runAgentTurn(TurnContext ctx, ChatRequest request, ResponseSink sink,
                              AgentTurnOverrides overrides);

    /**
     * Execute a high-risk chat tool after its Agent approval request is approved.
     *
     * <p>The current turn-service resume path is responsible for pending chat
     * continuation. Implementations may override this hook when they also store
     * approval-keyed pending tool payloads.
     *
     * @return a map with {@code handled=true} when a chat pending tool was found
     *         and consumed for the given approval PID.
     */
    default Map<String, Object> executeApprovedPendingTool(Long tenantId, String approvalPid) {
        return Map.of("handled", false);
    }
}
