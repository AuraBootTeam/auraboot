package com.auraboot.framework.agent.port;

import com.auraboot.framework.aurabot.dto.ChatMessage;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.aurabot.service.ChatSessionStore;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Port interface for ACP agent chat streaming.
 * <p>
 * Allows AuraBotChatService to delegate streaming chat to a named ACP agent
 * without compile-time coupling to a specific runtime implementation.
 * <p>
 * AgentChatPortImpl is the primary implementation. When unavailable, AuraBot falls through
 * to the default chat path.
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
     * Stream a chat response from a named ACP Agent via SSE.
     * <p>
     * The implementation should:
     * 1. Load the agent definition (system prompt, provider, model).
     * 2. Build LLM messages from history + current message.
     * 3. Resolve and bind agent tools.
     * 4. Run the tool loop and stream text chunks via the emitter.
     * 5. Complete or error the emitter when done.
     *
     * @param tenantId  current tenant ID
     * @param agentCode agent code to use
     * @param request   original chat request (message, history, pageContext, options)
     * @param emitter   SSE emitter to stream chunks to
     */
    void streamAgentChat(Long tenantId, String agentCode, ChatRequest request, SseEmitter emitter);

    /**
     * Resume a pending custom-agent tool after the user confirms or cancels it.
     *
     * @return true when this port handled the pending tool, false to let the caller
     *         fall back to the default AuraBot confirmation path.
     */
    default boolean resumeAgentToolAfterConfirmation(Long tenantId,
                                                     ChatSessionStore.PendingTool pending,
                                                     boolean confirmed,
                                                     SseEmitter emitter) {
        return false;
    }
}
