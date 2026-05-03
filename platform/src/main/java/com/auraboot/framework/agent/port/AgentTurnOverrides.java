package com.auraboot.framework.agent.port;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.provider.ToolDefinition;

import java.util.List;

/**
 * Server-only overrides bag for {@link AgentChatPort#runAgentTurn}. Lets a
 * trusted internal caller (e.g. {@code AgentReplyTask} for group-chat) pre-
 * build the LLM context and have {@code AgentChatPortImpl} use it instead of
 * its default per-step build path.
 *
 * <h2>Why server-only</h2>
 *
 * <p>The {@code ChatRequest} that {@code AuraBotController} accepts at
 * {@code @RequestBody} is a public DTO. Adding fields like
 * {@code customSystemPrompt} or {@code pinnedToolDefs} there would let a
 * client inject prompts and tool definitions — a prompt-injection / tool-
 * forgery / data-exfiltration vector. This class is the v5 answer to
 * Q-DC.1=A': caller-owned context via a server-internal object, NOT public
 * DTO fields. Per design v5 §10.7 Fix 1.
 *
 * <p>Construction is enforced server-only by:
 * <ul>
 *   <li>Living in {@code com.auraboot.framework.agent.port} (not
 *       {@code aurabot.dto}) — JSON deserialization paths do not target it.</li>
 *   <li>{@code @RequestBody} controllers do NOT construct it; they always
 *       pass {@code null} to {@link AgentChatPort#runAgentTurn}.</li>
 *   <li>The Builder class enforces immutability of the resulting instance.</li>
 * </ul>
 *
 * <h2>Field semantics — "if non-null, takes precedence over default"</h2>
 *
 * <ul>
 *   <li>{@link #systemPromptOverride()} — when non-null, used as the LLM
 *       system prompt verbatim. Default: {@code AgentChatPortImpl} builds
 *       from {@code ab_agent_definition.system_prompt}.</li>
 *   <li>{@link #messagesOverride()} — when non-null, used as the LLM message
 *       history. Default: {@code AgentChatPortImpl} restores from session
 *       tape or builds from {@code ChatRequest.history}.</li>
 *   <li>{@link #toolDefsOverride()} — when non-null, REPLACES the
 *       {@code ToolProviderRegistry.discoverAll(...)} discovery. Default:
 *       registry-based discovery. Use this when caller has a
 *       conversation-scoped tool list that the tenant-scoped registry can't
 *       express (e.g. group-chat agent's attached tools).</li>
 *   <li>{@link #extraTools()} — additional tool definitions that get merged
 *       on TOP of the discovered (or overridden) list. Name collisions go
 *       to extraTools (per DC.1 contract). Use for caller-injected dynamic
 *       tools like {@code transfer_to_agent} where the input schema depends
 *       on conversation membership.</li>
 *   <li>{@link #persistSessionTape()} — controls whether AgentChatPortImpl
 *       writes the post-turn tape to {@code ChatSessionStore}. Default: yes
 *       (matches existing aurabot behavior). Group-chat callers typically
 *       set this to {@code false} because the conversation history already
 *       lives in {@code ab_im_message}.</li>
 * </ul>
 *
 * <h2>Backward compat</h2>
 *
 * <p>{@code AgentChatPort.runAgentTurn(ctx, request, sink)} (3-arg default
 * method) calls the 4-arg variant with {@code overrides=null}, preserving
 * existing aurabot main path behavior. The DC.1 4-arg overload that took
 * {@code List<ToolDefinition> extraTools} is removed; its single
 * {@code extraTools} parameter is now {@link #extraTools()} on this object.
 * Existing test suites that supplied {@code List<ToolDefinition>} get
 * mechanically rewritten to {@code AgentTurnOverrides.builder().extraTools(...).build()}.
 *
 * <h2>Sunset criteria (DC.3d)</h2>
 *
 * <p>{@code AgentChatPortImpl} emits the
 * {@code agentchatport.caller_overrides_used{field=...}} counter every
 * time a non-null override field is consumed. The SPI {@code overrides}
 * parameter exists to support callers (notably group-chat
 * {@code AgentReplyTask}) that the tenant-scoped registry cannot serve;
 * once those call paths migrate to either:
 * <ul>
 *   <li>conversation-scoped tool registration in {@code ToolProviderRegistry}
 *       (so the registry path can express dynamic transfer_to_agent input
 *       schemas without caller injection), and</li>
 *   <li>history-source unification (so AgentChatPortImpl can rehydrate
 *       group-chat history from {@code ab_im_message} the same way it
 *       rehydrates aurabot tape from {@code ChatSessionStore}),</li>
 * </ul>
 * the counter trends to zero across all five {@code field} tag values.
 *
 * <p>When the counter reads zero across an entire release window for all
 * five fields, the {@code overrides} parameter on
 * {@link AgentChatPort#runAgentTurn} can be deprecated, the 3-arg variant
 * can become the only public surface, and this class can be removed. Until
 * then, {@code overrides=null} from REST callers is the fast path; the
 * security boundary in §10.7 Fix 1 / §3 of design v5 stays intact.
 */
public final class AgentTurnOverrides {

    private final String systemPromptOverride;
    private final List<LlmChatRequest.Message> messagesOverride;
    private final List<ToolDefinition> toolDefsOverride;
    private final List<ToolDefinition> extraTools;
    private final Boolean persistSessionTape;

    private AgentTurnOverrides(Builder b) {
        this.systemPromptOverride = b.systemPromptOverride;
        this.messagesOverride = b.messagesOverride;
        this.toolDefsOverride = b.toolDefsOverride;
        this.extraTools = b.extraTools;
        this.persistSessionTape = b.persistSessionTape;
    }

    public String systemPromptOverride() { return systemPromptOverride; }

    public List<LlmChatRequest.Message> messagesOverride() { return messagesOverride; }

    public List<ToolDefinition> toolDefsOverride() { return toolDefsOverride; }

    public List<ToolDefinition> extraTools() { return extraTools; }

    /** {@code null} = default (persist), {@code true} / {@code false} = explicit. */
    public Boolean persistSessionTape() { return persistSessionTape; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String systemPromptOverride;
        private List<LlmChatRequest.Message> messagesOverride;
        private List<ToolDefinition> toolDefsOverride;
        private List<ToolDefinition> extraTools;
        private Boolean persistSessionTape;

        private Builder() {}

        public Builder systemPromptOverride(String v) { this.systemPromptOverride = v; return this; }
        public Builder messagesOverride(List<LlmChatRequest.Message> v) { this.messagesOverride = v; return this; }
        public Builder toolDefsOverride(List<ToolDefinition> v) { this.toolDefsOverride = v; return this; }
        public Builder extraTools(List<ToolDefinition> v) { this.extraTools = v; return this; }
        public Builder persistSessionTape(Boolean v) { this.persistSessionTape = v; return this; }

        public AgentTurnOverrides build() {
            return new AgentTurnOverrides(this);
        }
    }
}
