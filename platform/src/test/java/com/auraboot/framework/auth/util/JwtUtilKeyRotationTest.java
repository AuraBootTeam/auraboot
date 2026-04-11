package com.auraboot.framework.auth.util;

import com.auraboot.framework.auth.dto.CustomUserDetails;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for JWT kid-based dual-key rotation in JwtUtil.
 */
class JwtUtilKeyRotationTest {

    private static final String SECRET_A = "secret-key-A-must-be-at-least-32-bytes-long!!";
    private static final String SECRET_B = "secret-key-B-must-be-at-least-32-bytes-long!!";
    private static final String KID_A = "key-a";
    private static final String KID_B = "key-b";

    private JwtUtil createUtil(String secret, String kid, String previousSecret, String previousKid) {
        JwtUtil util = new JwtUtil();
        ReflectionTestUtils.setField(util, "secret", secret);
        ReflectionTestUtils.setField(util, "kid", kid);
        ReflectionTestUtils.setField(util, "expiration", 3600L);
        ReflectionTestUtils.setField(util, "previousSecret", previousSecret != null ? previousSecret : "");
        ReflectionTestUtils.setField(util, "previousKid", previousKid != null ? previousKid : "");
        util.validateConfiguration();
        return util;
    }

    private CustomUserDetails mockUser(String userPid) {
        return new CustomUserDetails("testuser", "irrelevant", 1L, userPid,
                Collections.emptyList(), true, true, true, true);
    }

    // --- Token generation includes kid header ---

    @Test
    void generatedTokenContainsKidHeader() {
        JwtUtil util = createUtil(SECRET_A, KID_A, null, null);
        CustomUserDetails user = mockUser("pid-1");

        String token = util.generateTokenWithTenantId(user, "pid-1", 1L);
        String parsedKid = JwtUtil.parseKidFromHeader(token);

        assertThat(parsedKid).isEqualTo(KID_A);
    }

    @Test
    void tokenValidatesWithCurrentKey() {
        JwtUtil util = createUtil(SECRET_A, KID_A, null, null);
        CustomUserDetails user = mockUser("pid-1");

        String token = util.generateTokenWithTenantId(user, "pid-1", 1L);

        assertThat(util.validateToken(token, user)).isTrue();
        assertThat(util.extractUserPid(token)).isEqualTo("pid-1");
        assertThat(util.extractTenantId(token)).isEqualTo(1L);
    }

    // --- Dual-key rotation: old tokens still verify ---

    @Test
    void oldTokenVerifiesWithPreviousKeyDuringRotation() {
        // Phase 1: sign token with key-A
        JwtUtil utilBefore = createUtil(SECRET_A, KID_A, null, null);
        CustomUserDetails user = mockUser("pid-1");
        String oldToken = utilBefore.generateTokenWithTenantId(user, "pid-1", 1L, 1);

        // Phase 2: rotate — key-B is current, key-A is previous
        JwtUtil utilAfter = createUtil(SECRET_B, KID_B, SECRET_A, KID_A);

        // Old token (kid=key-a) should still validate
        assertThat(utilAfter.validateToken(oldToken, user)).isTrue();
        assertThat(utilAfter.extractUserPid(oldToken)).isEqualTo("pid-1");
        assertThat(utilAfter.extractSecurityVersion(oldToken)).isEqualTo(1);
    }

    @Test
    void newTokenUsesNewKeyDuringRotation() {
        JwtUtil util = createUtil(SECRET_B, KID_B, SECRET_A, KID_A);
        CustomUserDetails user = mockUser("pid-2");

        String newToken = util.generateTokenWithTenantId(user, "pid-2", 2L);

        assertThat(JwtUtil.parseKidFromHeader(newToken)).isEqualTo(KID_B);
        assertThat(util.validateToken(newToken, user)).isTrue();
    }

    // --- After rotation complete: old key removed ---

    @Test
    void oldTokenFailsAfterPreviousKeyRemoved() {
        // Sign with key-A
        JwtUtil utilBefore = createUtil(SECRET_A, KID_A, null, null);
        CustomUserDetails user = mockUser("pid-1");
        String oldToken = utilBefore.generateTokenWithTenantId(user, "pid-1", 1L);

        // Rotation complete: only key-B, no previous
        JwtUtil utilFinal = createUtil(SECRET_B, KID_B, null, null);

        assertThatThrownBy(() -> utilFinal.extractUserPid(oldToken))
                .isInstanceOf(io.jsonwebtoken.security.SignatureException.class);
    }

    // --- Backward compatibility: tokens without kid ---

    @Test
    void legacyTokenWithoutKidVerifiesWithCurrentKey() {
        // Simulate a pre-rotation token (no kid header) signed with current secret
        SecretKey key = Keys.hmacShaKeyFor(SECRET_A.getBytes(StandardCharsets.UTF_8));
        String legacyToken = Jwts.builder()
                .subject("pid-legacy")
                .claim("tenantId", 1L)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(key)
                .compact();

        JwtUtil util = createUtil(SECRET_A, KID_A, null, null);
        assertThat(JwtUtil.parseKidFromHeader(legacyToken)).isNull();
        assertThat(util.extractUserPid(legacyToken)).isEqualTo("pid-legacy");
        assertThat(util.extractTenantId(legacyToken)).isEqualTo(1L);
    }

    // --- Unknown kid is rejected ---

    @Test
    void unknownKidIsRejected() {
        // Sign with a completely different key/kid
        SecretKey rogueKey = Keys.hmacShaKeyFor("rogue-key-must-be-at-least-32-bytes-long!!!".getBytes(StandardCharsets.UTF_8));
        String rogueToken = Jwts.builder()
                .header().keyId("rogue-kid").and()
                .subject("pid-rogue")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(rogueKey)
                .compact();

        JwtUtil util = createUtil(SECRET_A, KID_A, null, null);

        assertThatThrownBy(() -> util.extractUserPid(rogueToken))
                .isInstanceOf(io.jsonwebtoken.security.SignatureException.class)
                .hasMessageContaining("Unknown kid");
    }

    // --- Tampered kid causes signature failure ---

    @Test
    void tamperedKidCausesSignatureFailure() {
        // Sign with key-A / kid-A
        JwtUtil util = createUtil(SECRET_A, KID_A, SECRET_B, KID_B);
        CustomUserDetails user = mockUser("pid-1");
        String token = util.generateTokenWithTenantId(user, "pid-1", 1L);

        // Tamper: replace kid-A with kid-B in the header so it routes to key-B,
        // but the signature was made with key-A → verification fails
        String[] parts = token.split("\\.");
        String headerJson = new String(java.util.Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String tampered = headerJson.replace(KID_A, KID_B);
        String newHeader = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tampered.getBytes(StandardCharsets.UTF_8));
        String tamperedToken = newHeader + "." + parts[1] + "." + parts[2];

        assertThatThrownBy(() -> util.extractUserPid(tamperedToken))
                .isInstanceOf(io.jsonwebtoken.security.SecurityException.class);
    }

    // --- parseKidFromHeader edge cases ---

    @Test
    void parseKidFromMalformedTokenReturnsNull() {
        assertThat(JwtUtil.parseKidFromHeader("not-a-jwt")).isNull();
        assertThat(JwtUtil.parseKidFromHeader("")).isNull();
        assertThat(JwtUtil.parseKidFromHeader(".payload.sig")).isNull();
    }

    // --- Rotation state inspection ---

    @Test
    void rotationStateReflectsConfiguration() {
        JwtUtil single = createUtil(SECRET_A, KID_A, null, null);
        assertThat(single.isRotationActive()).isFalse();
        assertThat(single.getCurrentKid()).isEqualTo(KID_A);

        JwtUtil rotating = createUtil(SECRET_B, KID_B, SECRET_A, KID_A);
        assertThat(rotating.isRotationActive()).isTrue();
        assertThat(rotating.getCurrentKid()).isEqualTo(KID_B);
    }
}
