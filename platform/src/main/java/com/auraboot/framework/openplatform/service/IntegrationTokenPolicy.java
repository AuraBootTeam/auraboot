package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.openplatform.entity.ApplicationAccessToken;
import java.time.Duration;
import java.time.Instant;

/** Explicit permanent lifetime is independent of rotation and revocation. */
public final class IntegrationTokenPolicy {
    private IntegrationTokenPolicy() { }

    public static Instant expiry(String mode, Instant requested, Instant now) {
        String selected = mode == null ? "fixed" : mode;
        if ("never".equals(selected)) {
            if (requested != null) throw new IllegalArgumentException("Permanent tokens cannot specify expiresAt");
            return null;
        }
        if (!"fixed".equals(selected)) throw new IllegalArgumentException("Unknown token expiration mode");
        Instant value = requested == null ? now.plus(Duration.ofDays(30)) : requested;
        if (value.isBefore(now.plus(Duration.ofHours(1))) || value.isAfter(now.plus(Duration.ofDays(90))))
            throw new IllegalArgumentException("Token validity must be between one hour and 90 days");
        return value;
    }

    public static Instant rotationCutoff(Instant now, Integer graceMinutes, Instant originalExpiry) {
        int minutes = graceMinutes == null ? 1440 : graceMinutes;
        if (minutes < 0 || minutes > 10080) throw new IllegalArgumentException("Rotation grace must be between zero and 10080 minutes");
        Instant value = now.plusSeconds(minutes * 60L);
        return originalExpiry == null || value.isBefore(originalExpiry) ? value : originalExpiry;
    }

    public static String status(ApplicationAccessToken token, Instant now) {
        if (token.getRevokedAt() != null) return "revoked";
        if (token.getAcceptUntil() != null && !token.getAcceptUntil().isAfter(now)) return "replaced";
        if (!Boolean.TRUE.equals(token.getNeverExpires()) && (token.getExpiresAt() == null || !token.getExpiresAt().isAfter(now))) return "expired";
        return token.getRotatedAt() == null ? "active" : "rotating";
    }
}
