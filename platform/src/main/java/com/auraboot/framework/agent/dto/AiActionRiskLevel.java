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

    /**
     * The same risk, said the way the rest of the agent runtime says it.
     *
     * <p>Two scales exist because they answer different questions: L0–L4 decides
     * whether the platform demands an approval, this one decides what the mobile
     * client shows a person. They are not redundant, but they were also never
     * connected — each was derived independently from the action type, so a
     * change to one silently disagreed with the other and nothing failed. This
     * is the single place the two meet, so a divergence has to be written down
     * here rather than discovered in production.
     *
     * <p>L4 is irreversible and L3 external: both warrant the full-screen
     * confirmation. BLOCKED has no L-equivalent — it means "never offer this",
     * a decision taken before risk is scored — so it maps to the highest level
     * rather than inventing an L5.
     */
    public String toPlatformRiskLevel() {
        return switch (this) {
            case LOW -> "L0";
            case MEDIUM -> "L1";
            case HIGH -> "L3";
            case BLOCKED -> "L4";
        };
    }

    /** Reads a platform L0–L4 level as the confirmation strength a client should use. */
    public static AiActionRiskLevel fromPlatformRiskLevel(String platformLevel) {
        if (platformLevel == null) {
            return MEDIUM;
        }
        return switch (platformLevel.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "L0" -> LOW;
            case "L1", "L2" -> MEDIUM;
            case "L3" -> HIGH;
            case "L4" -> BLOCKED;
            // Unknown means unrecognised, not safe. Defaulting down would turn a
            // scale we failed to parse into an action nobody was asked about.
            default -> HIGH;
        };
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
