package com.auraboot.framework.agent.identity;

/**
 * The subject that caused an agent execution to start.
 *
 * <p>The initiator is attribution, not runtime authority. A digital employee
 * keeps this identity for approvals, notifications and audit while executing
 * with its own {@link ExecutionPrincipal} actor.
 */
public record Initiator(
        Type type,
        Long userId,
        Long memberId,
        String channel
) {

    public enum Type {
        HUMAN,
        SYSTEM,
        SCHEDULE,
        EVENT,
        AGENT_HANDOFF
    }

    public Initiator {
        if (type == null) {
            throw new IllegalArgumentException("initiator type is required");
        }
        if (type == Type.HUMAN
                && (userId == null || userId <= 0L || memberId == null || memberId <= 0L)) {
            throw new IllegalArgumentException(
                    "human initiator requires positive userId and memberId");
        }
    }

    public static Initiator human(Long userId, Long memberId, String channel) {
        return new Initiator(Type.HUMAN, userId, memberId, channel);
    }
}
