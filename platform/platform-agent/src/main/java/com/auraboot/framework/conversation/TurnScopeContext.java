package com.auraboot.framework.conversation;

/**
 * ThreadLocal carrier for <em>which conversation the current turn belongs to</em>, so that a tool
 * executing inside the turn can act on it.
 *
 * <p>A {@code ToolProvider} is handed only {@code (tenantId, toolCode, params)}, and the params come
 * from the LLM. That is fine for a tool whose subject is stated in the prompt ("create a lead named
 * X"), but not for a tool whose subject <em>is the conversation itself</em> — escalating to a human,
 * summarising the thread, rating the answer. The model cannot supply a conversation id: it has never
 * seen one, so asking it to would be asking it to invent one.
 *
 * <p>So the chokepoint publishes it. {@link ConversationTurnService#runTurn} binds the scope before
 * dispatching and clears it in a finally, exactly as {@link ResponseSinkContext} does for the sink.
 * Both dispatch paths (aurabot and named-agent) run on the calling thread, so a tool executing
 * within the turn sees it.
 *
 * <p>Absent by design outside a turn (cron, ad-hoc skill invocation, tests): {@link #get()} returns
 * {@code null} and a caller must treat that as "not in a conversation", never as an error.
 *
 * @param conversationId {@code ab_im_conversation.id} of the running turn; null for turns not bound
 *                       to a conversation
 * @param channel        the request channel (e.g. {@code cs_widget}, {@code web}), the discriminator
 *                       a provider uses to decide whether its tools apply here
 */
public record TurnScopeContext(Long conversationId, String channel) {

    private static final ThreadLocal<TurnScopeContext> CURRENT = new ThreadLocal<>();

    /** Bind the current turn's scope. Must be paired with {@link #clear()} in a finally block. */
    public static void set(Long conversationId, String channel) {
        CURRENT.set(new TurnScopeContext(conversationId, channel));
    }

    /** The current turn's scope, or {@code null} when no turn is bound. */
    public static TurnScopeContext get() {
        return CURRENT.get();
    }

    /** The conversation the current turn belongs to, or {@code null} when no turn is bound. */
    public static Long currentConversationId() {
        TurnScopeContext scope = CURRENT.get();
        return scope == null ? null : scope.conversationId();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
