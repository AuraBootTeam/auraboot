package com.auraboot.framework.agent.profile;

/**
 * User Soul Profile lifecycle status enumeration.
 *
 * <p>Stored in {@code ab_agent_user_soul_profile.status} as the lowercase
 * {@link #code()}. Per project red-line "禁止魔术字符串", DB string values must
 * be lowercase; callers must never persist {@link Enum#name()} (which is
 * uppercase) and must never read via {@link Enum#valueOf(Class, String)} on a
 * raw DB value. Always round-trip through {@link #code()} / {@link #fromCode}.
 *
 * <p>State transitions (see plan §5.2 and
 * {@code 2026-04-19-user-soul-profile-design.md}):
 * <pre>
 *   DRAFT ──(activator after shadow period)──▶ ACTIVE
 *   ACTIVE ──(new DRAFT activated)──▶ SUPERSEDED
 *   * ──(forget cascade)──▶ ARCHIVED
 * </pre>
 */
public enum UserSoulProfileStatus {

    /** Just-derived, awaiting the shadow period before activation. */
    DRAFT("draft"),

    /** Current visible profile; at most one ACTIVE row per (tenant, user). */
    ACTIVE("active"),

    /** Previously ACTIVE, kept for audit. */
    SUPERSEDED("superseded"),

    /** GDPR-forgotten (tombstoned) or otherwise terminally retired. */
    ARCHIVED("archived");

    private final String code;

    UserSoulProfileStatus(String code) {
        this.code = code;
    }

    /**
     * Returns the lowercase database value.
     */
    public String code() {
        return code;
    }

    /**
     * Parse from database value. Strict: case-sensitive match against the
     * lowercase code; unknown values throw.
     */
    public static UserSoulProfileStatus fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("UserSoulProfileStatus code must not be null");
        }
        for (UserSoulProfileStatus s : values()) {
            if (s.code.equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown UserSoulProfileStatus code: " + code);
    }
}
