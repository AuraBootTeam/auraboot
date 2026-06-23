package com.auraboot.framework.behavior.sitekey;

import java.security.SecureRandom;

/**
 * Generates public, non-secret site keys for anonymous behavior telemetry.
 *
 * <p>Format: {@code abk_} prefix + {@value #KEY_LENGTH} base62 characters. The
 * {@code abk_} prefix makes keys greppable in logs and recognizable in embedded
 * app HTML (GA {@code measurementId} style). Keys are public identifiers — not
 * secrets — but are still unguessable (drawn from {@link SecureRandom}) so an
 * attacker cannot enumerate other tenants' keys. Real abuse protection (origin
 * allowlist + rate limiting) lands in SP2; unguessability is the first layer.
 *
 * <p>{@value #KEY_LENGTH} base62 chars ≈ 190 bits of entropy, so collisions are
 * negligible; the create handler additionally checks cross-tenant uniqueness and
 * the {@code site_key} column carries a per-tenant unique index as a backstop.
 */
public final class SiteKeyGenerator {

    /** Public key prefix — greppable, GA measurementId style. */
    public static final String PREFIX = "abk_";

    /** Number of random base62 characters after the prefix. */
    static final int KEY_LENGTH = 32;

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final SecureRandom RNG = new SecureRandom();

    private SiteKeyGenerator() {
    }

    /**
     * Generate a new public site key, e.g. {@code abk_3kQ9...}.
     *
     * @return a fresh {@code abk_}-prefixed base62 key
     */
    public static String generate() {
        StringBuilder sb = new StringBuilder(PREFIX.length() + KEY_LENGTH);
        sb.append(PREFIX);
        for (int i = 0; i < KEY_LENGTH; i++) {
            // SecureRandom.nextInt(bound) is unbiased — no modulo skew across the alphabet.
            sb.append(ALPHABET.charAt(RNG.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
