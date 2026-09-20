package com.auraboot.framework.openplatform.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/** Authenticated protocol tokens for public cursors and strong resource ETags. */
@Component
public class OpenApiProtocolTokenCodec {
    private static final String VERSION = "ab1";
    private static final Duration CURSOR_TTL = Duration.ofHours(24);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final String configuredSecret;
    private final String activeProfile;
    private final Clock clock;
    private byte[] signingKey;

    @Autowired
    public OpenApiProtocolTokenCodec(
            @Value("${open-platform.protocol-signing-key:${security.field-encryption.key:}}") String configuredSecret,
            @Value("${spring.profiles.active:}") String activeProfile) {
        this(configuredSecret, activeProfile, Clock.systemUTC());
    }

    public OpenApiProtocolTokenCodec(String configuredSecret, String activeProfile, Clock clock) {
        this.configuredSecret = configuredSecret;
        this.activeProfile = activeProfile;
        this.clock = clock;
    }

    @PostConstruct
    void init() {
        String material = configuredSecret;
        if (material == null || material.isBlank()) {
            if (!isDevelopmentProfile(activeProfile)) {
                throw new IllegalStateException("Open Platform protocol signing key is required outside dev/test");
            }
            // Development tokens need only be stable for this process. Production always fails closed above.
            material = VERSION + ":development-process:" + System.identityHashCode(this) + ":" + System.nanoTime();
        }
        signingKey = sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    public String encodeCursor(String resourceCode, int schemaVersion, String lastPid) {
        long issuedAt = clock.instant().getEpochSecond();
        return token("cursor", resourceCode, Integer.toString(schemaVersion), lastPid, Long.toString(issuedAt));
    }

    public String decodeCursor(String token, String resourceCode, int schemaVersion) {
        String[] parts = verify(token, "cursor", 5);
        if (!resourceCode.equals(parts[1]) || !Integer.toString(schemaVersion).equals(parts[2])) {
            throw new IllegalArgumentException("Cursor does not belong to this resource schema");
        }
        long issuedAt = parseLong(parts[4], "Invalid cursor timestamp");
        Instant now = clock.instant();
        Instant issued = Instant.ofEpochSecond(issuedAt);
        if (issued.isAfter(now.plus(CLOCK_SKEW)) || issued.isBefore(now.minus(CURSOR_TTL))) {
            throw new IllegalArgumentException("Cursor has expired");
        }
        if (parts[3].isBlank()) {
            throw new IllegalArgumentException("Invalid cursor continuation");
        }
        return parts[3];
    }

    public String encodeEtag(String resourceCode, String recordPid, long rowVersion) {
        return '"' + token("etag", resourceCode, recordPid, Long.toString(rowVersion)) + '"';
    }

    public long decodeEtag(String etag, String resourceCode, String recordPid) {
        if (etag == null || etag.length() < 3 || etag.startsWith("W/")
                || etag.charAt(0) != '"' || etag.charAt(etag.length() - 1) != '"') {
            throw new OpenApiPreconditionException("A strong If-Match ETag is required");
        }
        String[] parts;
        try {
            parts = verify(etag.substring(1, etag.length() - 1), "etag", 4);
        } catch (IllegalArgumentException exception) {
            throw new OpenApiPreconditionException("If-Match ETag is invalid", exception);
        }
        if (!resourceCode.equals(parts[1]) || !recordPid.equals(parts[2])) {
            throw new OpenApiPreconditionException("If-Match ETag does not belong to this resource");
        }
        long version;
        try {
            version = parseLong(parts[3], "Invalid ETag version");
        } catch (IllegalArgumentException exception) {
            throw new OpenApiPreconditionException("If-Match ETag is invalid", exception);
        }
        if (version < 1 || version > Integer.MAX_VALUE) {
            throw new OpenApiPreconditionException("If-Match ETag version is invalid");
        }
        return version;
    }

    private String token(String... claims) {
        String payload = String.join("\n", claims);
        String encoded = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return VERSION + "." + encoded + "." + ENCODER.encodeToString(hmac(encoded));
    }

    private String[] verify(String token, String expectedType, int expectedClaims) {
        ensureInitialized();
        String[] tokenParts = token == null ? new String[0] : token.split("\\.", -1);
        if (tokenParts.length != 3 || !VERSION.equals(tokenParts[0])) {
            throw new IllegalArgumentException("Invalid Open Platform protocol token");
        }
        byte[] supplied;
        byte[] payload;
        try {
            supplied = DECODER.decode(tokenParts[2]);
            payload = DECODER.decode(tokenParts[1]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid Open Platform protocol token", exception);
        }
        if (!MessageDigest.isEqual(hmac(tokenParts[1]), supplied)) {
            throw new IllegalArgumentException("Invalid Open Platform protocol token signature");
        }
        String[] claims = new String(payload, StandardCharsets.UTF_8).split("\n", -1);
        if (claims.length != expectedClaims || !expectedType.equals(claims[0])) {
            throw new IllegalArgumentException("Invalid Open Platform protocol token purpose");
        }
        return claims;
    }

    private byte[] hmac(String encodedPayload) {
        ensureInitialized();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(encodedPayload.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) {
            throw new IllegalStateException("Open Platform protocol token signing failed", exception);
        }
    }

    private void ensureInitialized() {
        if (signingKey == null) {
            init();
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static long parseLong(String value, String message) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }

    private static boolean isDevelopmentProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            return true;
        }
        String normalized = profile.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("dev") || normalized.contains("local") || normalized.contains("test");
    }
}
