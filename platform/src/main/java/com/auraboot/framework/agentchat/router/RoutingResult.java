package com.auraboot.framework.agentchat.router;

import java.util.List;

/**
 * Result of GroupChatAgentRouter.resolveTargetAgents:
 * - targetAgentId: which agent should actually run (null = no AI response)
 * - bypassedMentionedAgentIds: agents that were @-mentioned but demoted to context-summary
 * - priority: which rule fired (P0/P1/P2 for trace logs)
 *
 * <p>G1 design: multi-mention is collapsed to single target (first mentioned) so users
 * see only one AI reply per turn. Bypassed agents are passed to AgentReplyTask context
 * so the target agent can reference their capabilities in its response.
 */
public record RoutingResult(
        Long targetAgentId,
        List<Long> bypassedMentionedAgentIds,
        String priority
) {
    public static RoutingResult none() {
        return new RoutingResult(null, List.of(), null);
    }
}
