package com.auraboot.framework.aurabot.skill;

import java.util.Locale;

/**
 * Persisted lifecycle status for {@code ab_aurabot_skill_run}.
 *
 * <p>Stored via {@link #code()} (lowercase) — never via {@link #name()}.
 * AGENTS.md "魔术字符串" red-line: only {@code code()} values may live in
 * the DB; readers must round-trip via {@link #fromCode(String)}.
 */
public enum SkillRunStatus {
    /** Skill executed successfully and is reversible until TTL elapses. */
    SUCCESS("success"),
    /** Skill was reversed via {@code POST /skill/undo} or batch-undo. */
    UNDONE("undone"),
    /** Skill execution recorded a terminal failure. */
    FAILED("failed");

    private final String code;

    SkillRunStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * Resolve a stored {@code code} back to its enum constant.
     *
     * @throws IllegalArgumentException when {@code code} is null/blank or unknown.
     */
    public static SkillRunStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("SkillRunStatus code must not be blank");
        }
        String norm = code.trim().toLowerCase(Locale.ROOT);
        for (SkillRunStatus s : values()) {
            if (s.code.equals(norm)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown SkillRunStatus code: " + code);
    }
}
