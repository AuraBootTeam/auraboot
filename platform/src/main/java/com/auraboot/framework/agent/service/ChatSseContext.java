package com.auraboot.framework.agent.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ThreadLocal carrier for the current chat turn's SSE emitter so tool
 * execution code (ToolLoopService and below) can push ResultContract events
 * to the frontend without taking emitter parameters through the port
 * interface.
 *
 * Set by AuraBotChatService at the start of a turn, cleared in finally.
 * Read by ResultContractEmitter. Safe to ignore when no context is set —
 * non-chat callers (ad-hoc skill invocations, tests) simply won't emit.
 */
public final class ChatSseContext {

    private static final ThreadLocal<SseEmitter> EMITTER = new ThreadLocal<>();

    private ChatSseContext() {}

    public static void setEmitter(SseEmitter emitter) {
        EMITTER.set(emitter);
    }

    public static SseEmitter getEmitter() {
        return EMITTER.get();
    }

    public static void clear() {
        EMITTER.remove();
    }
}
