package com.auraboot.framework.auth.util;

import com.auraboot.framework.auth.dto.CustomUserDetails;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtUtil {

    private static final int MIN_SECRET_BYTES = 32; // 256 bits for HMAC-SHA256
    private static final long MAX_EXPIRATION_SECONDS = 7 * 24 * 3600; // 7 days

    @Value("${security.jwt.secret}")
    private String secret;

    @Value("${security.jwt.expiration}")
    private Long expiration;

    @PostConstruct
    void validateConfiguration() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret must not be blank. Set security.jwt.secret in application.yml or environment.");
        }
        int keyBytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (keyBytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                String.format("JWT secret too short: %d bytes (minimum %d bytes / 256 bits). "
                    + "Use a cryptographically random string of at least 32 characters.", keyBytes, MIN_SECRET_BYTES));
        }
        if (expiration != null && expiration > MAX_EXPIRATION_SECONDS) {
            throw new IllegalStateException(
                String.format("JWT expiration %d seconds exceeds maximum allowed %d seconds (7 days).", expiration, MAX_EXPIRATION_SECONDS));
        }
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
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
        String subject = extractIdentifier(token);
        return subject;

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
                .claims(claims)
                .subject(subjectByUserPid)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(expiration)))
                .signWith(getSigningKey())
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
        // 使用userPid进行验证，而不是username
        final String tokenUserPid = extractUserPid(token);
        final String userPid = ((CustomUserDetails) userDetails).getUserPid();
        return (tokenUserPid.equals(userPid) && !isTokenExpired(token));
    }


    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(Date.from(Instant.now()));
    }





}