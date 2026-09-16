package com.auraboot.framework.auth.util;

import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.dto.SessionTokenContext;
import io.jsonwebtoken.Claims;
import com.auraboot.framework.common.util.UlidGenerator;
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
import java.time.Clock;
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

    @Value("${security.session.renew-window-seconds:15552000}")
    private long sessionLifetimeSeconds = 15552000L;

    @Value("${security.jwt.previous-secret:}")
    private String previousSecret;

    @Value("${security.jwt.previous-kid:}")
    private String previousKid;

    private Clock clock = Clock.systemUTC();
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
        if (expiration == null || expiration <= 0 || expiration > MAX_EXPIRATION_SECONDS) {
            throw new IllegalStateException(
                String.format("JWT expiration %d seconds exceeds maximum allowed %d seconds (7 days).", expiration, MAX_EXPIRATION_SECONDS));
        }

        if (sessionLifetimeSeconds <= 0) throw new IllegalStateException("Session lifetime must be positive");
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
                .clock(() -> Date.from(clock.instant()))
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

    public Long extractApplicationId(String token) {
        return extractLongClaim(token, "applicationId");
    }

    public Long extractLoginChannelId(String token) {
        return extractLongClaim(token, "loginChannelId");
    }

    public Long extractActorPartyId(String token) {
        return extractLongClaim(token, "actorPartyId");
    }

    public Long extractPartyMembershipId(String token) {
        return extractLongClaim(token, "partyMembershipId");
    }

    public String extractExecutionScope(String token) {
        return extractStringClaim(token, "executionScope");
    }

    public String extractSessionStage(String token) {
        return extractStringClaim(token, "sessionStage");
    }

    public long extractContextVersion(String token) {
        Long value = extractLongClaim(token, "cv");
        return value == null ? 1 : value;
    }

    private Long extractLongClaim(String token, String name) {
        return extractClaim(token, claims -> {
            Object value = claims.get(name);
            return value == null ? null : Long.valueOf(value.toString());
        });
    }

    private String extractStringClaim(String token, String name) {
        return extractClaim(token, claims -> {
            Object value = claims.get(name);
            return value == null ? null : value.toString();
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

    public String extractSessionId(String token) {
        return extractStringClaim(token, "sid");
    }

    /** Preserve the authenticated login deadline when changing execution context. */
    public String inheritSessionLifetime(String newToken, String previousToken) {
        Claims previous = extractAllClaims(previousToken);
        Map<String, Object> next = new HashMap<>(extractAllClaims(newToken));
        if (!previous.getSubject().equals(next.get("sub"))) {
            throw new IllegalArgumentException("Session subject mismatch");
        }
        copyLifetime(previous, next);
        return createToken(next, previous.getSubject());
    }

    /** Verification rejects expired tokens; renewal never revives one. */
    public String renewSessionToken(String token, String sessionPid) {
        Claims previous = extractAllClaims(token);
        if (!previous.getExpiration().toInstant().isAfter(clock.instant())) throw new IllegalArgumentException("Token expired");
        if (previous.get("scope") != null) throw new IllegalArgumentException("Scoped tokens cannot renew login sessions");
        Map<String, Object> claims = new HashMap<>(previous);
        copyLifetime(previous, claims);
        claims.put("sid", sessionPid);
        return createToken(claims, previous.getSubject());
    }

    private void copyLifetime(Claims previous, Map<String, Object> next) {
        Object started = previous.get("auth_time");
        long origin = started == null ? previous.getIssuedAt().toInstant().getEpochSecond()
                : Long.parseLong(started.toString());
        Object deadline = previous.get("session_exp");
        next.put("auth_time", origin);
        next.put("session_exp", deadline == null ? origin + sessionLifetimeSeconds
                : Long.parseLong(deadline.toString()));
    }

    private String createToken(Map<String, Object> claims, String subjectByUserPid) {
        Instant now = clock.instant();
        claims.putIfAbsent("sid", UlidGenerator.generate());
        claims.putIfAbsent("auth_time", now.getEpochSecond());
        claims.putIfAbsent("session_exp", now.plusSeconds(sessionLifetimeSeconds).getEpochSecond());
        Instant deadline = Instant.ofEpochSecond(Long.parseLong(claims.get("session_exp").toString()));
        if (!deadline.isAfter(now)) throw new IllegalArgumentException("Absolute session deadline reached");
        Instant tokenExpiry = now.plusSeconds(expiration);
        if (tokenExpiry.isAfter(deadline)) tokenExpiry = deadline;
        return Jwts.builder()
                .header().keyId(kid).and()
                .claims(claims)
                .subject(subjectByUserPid)
                // Unique per issuance so two tokens minted in the same second
                // still differ while sharing a revocable server-side session.
                .id(java.util.UUID.randomUUID().toString())
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(tokenExpiry))
                .signWith(currentKey)
                .compact();
    }

    /**
     * The {@code scope} claim, or null when the token does not carry one.
     *
     * <p>A token without a scope is an ordinary user token and gets the full authenticated
     * surface. A token with a scope is confined to whatever {@code TokenScopePolicy} declares
     * for it — see {@code ScopeRestrictionFilter}.
     */
    public String extractScope(String token) {
        return extractClaim(token, claims -> {
            Object scope = claims.get("scope");
            return scope != null ? scope.toString() : null;
        });
    }

    /**
     * Mint a scoped token for a subject that is <em>not</em> a platform user — an embedded-widget
     * visitor, say. Such a subject cannot be resolved by {@code UnifiedUserDetailsService}, so a
     * scoped token must never reach {@code JwtAuthenticationFilter}; {@code ScopeRestrictionFilter}
     * confines it to the paths its policy allows.
     *
     * <p>TTL is explicit rather than the global {@code security.jwt.expiration} (24h): a token
     * handed to an anonymous browser on a third-party origin should live minutes, not a day.
     */
    public String generateScopedToken(String subject, String scope, Map<String, Object> extraClaims, long ttlSeconds) {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("scope is required for a scoped token");
        }
        Map<String, Object> claims = new HashMap<>();
        if (extraClaims != null) {
            claims.putAll(extraClaims);
        }
        claims.put("scope", scope);
        return Jwts.builder()
                .header().keyId(kid).and()
                .claims(claims)
                .subject(subject)
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(Instant.now().plusSeconds(ttlSeconds)))
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
        return generateTokenWithContext(
                userDetails,
                userPid,
                SessionTokenContext.tenant(tenantId, memberId, securityVersion));
    }

    public String generateTokenWithContext(
            UserDetails userDetails,
            String userPid,
            SessionTokenContext context) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("name", userDetails.getUsername());
        if (context.tenantId() != null) {
            claims.put("tenantId", context.tenantId());
        }
        if (context.memberId() != null) {
            claims.put("memberId", context.memberId());
        }
        if (context.applicationId() != null) {
            claims.put("applicationId", context.applicationId());
        }
        if (context.loginChannelId() != null) {
            claims.put("loginChannelId", context.loginChannelId());
        }
        if (context.executionScope() != null) {
            claims.put("executionScope", context.executionScope().getCode());
        }
        if (context.actorPartyId() != null) {
            claims.put("actorPartyId", context.actorPartyId());
        }
        if (context.partyMembershipId() != null) {
            claims.put("partyMembershipId", context.partyMembershipId());
        }
        if (context.sessionStage() != null) {
            claims.put("sessionStage", context.sessionStage().getCode());
        }
        claims.put("cv", Math.max(1, context.contextVersion()));
        if (context.securityVersion() > 0) {
            claims.put("sv", context.securityVersion());
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
