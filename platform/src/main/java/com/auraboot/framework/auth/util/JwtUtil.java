package com.auraboot.framework.auth.util;

import com.auraboot.framework.auth.dto.CustomUserDetails;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * JWT utility with dual-key rotation support via kid (Key ID) header.
 *
 * <p>Rotation flow:
 * <ol>
 *   <li>Normal: single key (secret + kid) signs and verifies all tokens</li>
 *   <li>Rotate: set previous-secret/previous-kid, deploy — new tokens use new key,
 *       old tokens still verify against previous key</li>
 *   <li>After one token lifetime (expiration), remove previous-secret/previous-kid</li>
 * </ol>
 */
@Slf4j
@Component
public class JwtUtil {

    private static final int MIN_SECRET_BYTES = 32; // 256 bits for HMAC-SHA256
    private static final long MAX_EXPIRATION_SECONDS = 7 * 24 * 3600; // 7 days
    private static final String DEV_DEFAULT_SECRET = "dev-only-secret-key-replace-in-production-min-32-chars";

    @Value("${security.jwt.secret}")
    private String secret;

    @Value("${spring.profiles.active:}")
    private String activeProfile;

    @Value("${security.jwt.kid:key-1}")
    private String kid;

    @Value("${security.jwt.expiration}")
    private Long expiration;

    @Value("${security.jwt.previous-secret:}")
    private String previousSecret;

    @Value("${security.jwt.previous-kid:}")
    private String previousKid;

    private SecretKey currentKey;
    private SecretKey previousKey; // null when not in rotation

    @PostConstruct
    void validateConfiguration() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret must not be blank. Set security.jwt.secret in application.yml or environment.");
        }
        // Reject the default dev key in non-dev profiles
        if (DEV_DEFAULT_SECRET.equals(secret) && !isDevProfile()) {
            throw new IllegalStateException(
                "JWT secret is the default dev key. Set JWT_SECRET environment variable for non-dev profiles. "
                + "Generate with: openssl rand -base64 64");
        }
        if (DEV_DEFAULT_SECRET.equals(secret)) {
            log.warn("Using default dev JWT secret — DO NOT use in production.");
        }
        validateKeyLength(secret, "security.jwt.secret");
        if (expiration != null && expiration > MAX_EXPIRATION_SECONDS) {
            throw new IllegalStateException(
                String.format("JWT expiration %d seconds exceeds maximum allowed %d seconds (7 days).", expiration, MAX_EXPIRATION_SECONDS));
        }

        currentKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        if (previousSecret != null && !previousSecret.isBlank()) {
            validateKeyLength(previousSecret, "security.jwt.previous-secret");
            previousKey = Keys.hmacShaKeyFor(previousSecret.getBytes(StandardCharsets.UTF_8));
            log.info("JWT key rotation active: current kid={}, previous kid={}", kid, previousKid);
        } else {
            previousKey = null;
        }
    }

    private void validateKeyLength(String key, String configName) {
        int keyBytes = key.getBytes(StandardCharsets.UTF_8).length;
        if (keyBytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                String.format("%s too short: %d bytes (minimum %d bytes / 256 bits). "
                    + "Use a cryptographically random string of at least 32 characters.", configName, keyBytes, MIN_SECRET_BYTES));
        }
    }

    /**
     * Resolve the signing key for verification based on the token's kid header.
     * Tokens without kid are treated as current key (backward compatible with pre-rotation tokens).
     */
    SecretKey resolveSigningKey(String token) {
        String tokenKid = parseKidFromHeader(token);

        // No kid in token — legacy token, verify with current key
        if (tokenKid == null) {
            return currentKey;
        }
        if (kid.equals(tokenKid)) {
            return currentKey;
        }
        if (previousKey != null && tokenKid.equals(previousKid)) {
            return previousKey;
        }
        throw new io.jsonwebtoken.security.SignatureException("Unknown kid: " + tokenKid);
    }

    /**
     * Parse kid from JWT header without verifying signature.
     * Safe because kid is only used to select which key to verify with —
     * a tampered kid simply causes signature verification to fail.
     */
    static String parseKidFromHeader(String token) {
        int firstDot = token.indexOf('.');
        if (firstDot <= 0) {
            return null;
        }
        try {
            String headerJson = new String(
                Base64.getUrlDecoder().decode(token.substring(0, firstDot)),
                StandardCharsets.UTF_8);
            // Minimal JSON parsing for {"...","kid":"value","..."}
            int kidIdx = headerJson.indexOf("\"kid\"");
            if (kidIdx < 0) {
                return null;
            }
            int colonIdx = headerJson.indexOf(':', kidIdx + 5);
            if (colonIdx < 0) {
                return null;
            }
            int quoteStart = headerJson.indexOf('"', colonIdx + 1);
            if (quoteStart < 0) {
                return null;
            }
            int quoteEnd = headerJson.indexOf('"', quoteStart + 1);
            if (quoteEnd < 0) {
                return null;
            }
            return headerJson.substring(quoteStart + 1, quoteEnd);
        } catch (IllegalArgumentException e) {
            // Malformed Base64
            return null;
        }
    }

    private Claims extractAllClaims(String token) {
        SecretKey key = resolveSigningKey(token);
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public String extractIdentifier(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String extractUserPid(String token) {
        return extractIdentifier(token);
    }

    public Long extractTenantId(String token) {
        return extractClaim(token, claims -> {
            Object tenantId = claims.get("tenantId");
            return tenantId != null ? Long.valueOf(tenantId.toString()) : null;
        });
    }

    public Long extractMemberId(String token) {
        return extractClaim(token, claims -> {
            Object memberId = claims.get("memberId");
            return memberId != null ? Long.valueOf(memberId.toString()) : null;
        });
    }

    /**
     * Extract security version from token. Returns 0 if not present (backward compatible).
     */
    public int extractSecurityVersion(String token) {
        return extractClaim(token, claims -> {
            Object sv = claims.get("sv");
            return sv != null ? Integer.parseInt(sv.toString()) : 0;
        });
    }

    private String createToken(Map<String, Object> claims, String subjectByUserPid) {
        return Jwts.builder()
                .header().keyId(kid).and()
                .claims(claims)
                .subject(subjectByUserPid)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(expiration)))
                .signWith(currentKey)
                .compact();
    }

    public String generateTokenWithTenantId(UserDetails userDetails, String userPid, Long tenantId) {
        return generateTokenWithTenantId(userDetails, userPid, tenantId, null, 0);
    }

    public String generateTokenWithTenantId(UserDetails userDetails, String userPid, Long tenantId, int securityVersion) {
        return generateTokenWithTenantId(userDetails, userPid, tenantId, null, securityVersion);
    }

    public String generateTokenWithTenantId(UserDetails userDetails, String userPid, Long tenantId, Long memberId, int securityVersion) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("name", userDetails.getUsername());
        if (tenantId != null) {
            claims.put("tenantId", tenantId);
        }
        if (memberId != null) {
            claims.put("memberId", memberId);
        }
        if (securityVersion > 0) {
            claims.put("sv", securityVersion);
        }
        return createToken(claims, userPid);
    }

    public Boolean validateToken(String token, UserDetails userDetails) {
        final String tokenUserPid = extractUserPid(token);
        final String userPid = ((CustomUserDetails) userDetails).getUserPid();
        return (tokenUserPid.equals(userPid) && !isTokenExpired(token));
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(Date.from(Instant.now()));
    }

    /** Visible for testing — returns the current kid. */
    String getCurrentKid() {
        return kid;
    }

    /** Visible for testing — returns whether rotation is active. */
    boolean isRotationActive() {
        return previousKey != null;
    }

    private boolean isDevProfile() {
        if (activeProfile == null || activeProfile.isBlank()) {
            return true; // No profile = local dev
        }
        return activeProfile.contains("dev") || activeProfile.contains("local")
                || activeProfile.contains("test") || activeProfile.contains("integration-test");
    }
}
