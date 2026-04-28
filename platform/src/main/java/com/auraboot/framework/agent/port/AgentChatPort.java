package com.auraboot.framework.agent.port;

import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;

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
    TurnOutcome runAgentTurn(TurnContext ctx, ChatRequest request, ResponseSink sink);
}
