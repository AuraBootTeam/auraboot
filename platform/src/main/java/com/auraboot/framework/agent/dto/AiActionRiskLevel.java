package com.auraboot.framework.agent.dto;

/**
 * Risk level for AI-suggested actions.
 * Determines the confirmation UI shown to the user on mobile clients.
 *
 * <ul>
 *   <li>{@code LOW} — no confirmation needed (copy, navigate)</li>
 *   <li>{@code MEDIUM} — standard confirmation dialog (create_task)</li>
 *   <li>{@code HIGH} — full-screen confirmation with secondary confirm (state_transition, delete via execute_command)</li>
 *   <li>{@code BLOCKED} — action is forbidden (AI must not suggest it)</li>
 * </ul>
 */
public enum AiActionRiskLevel {

    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    BLOCKED("blocked");

    private final String code;

    AiActionRiskLevel(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static AiActionRiskLevel fromCode(String code) {
        if (code == null) return LOW;
        for (AiActionRiskLevel level : values()) {
            if (level.code.equals(code)) {
                return level;
            }
        }
        return LOW;
    }
}
