package com.auraboot.framework.auth.util;

import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.entity.UserSession;
import com.auraboot.framework.auth.mapper.UserSessionMapper;
import com.auraboot.framework.auth.service.impl.SessionManagementServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtSessionLifetimeTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private final JwtUtil jwt = new JwtUtil();
    private final CustomUserDetails user = new CustomUserDetails("test", "", 1L, "user",
            Collections.emptyList(), true, true, true, true);
    JwtSessionLifetimeTest() {
        ReflectionTestUtils.setField(jwt, "secret", "test-session-secret-at-least-32-bytes-long");
        ReflectionTestUtils.setField(jwt, "kid", "test");
        ReflectionTestUtils.setField(jwt, "expiration", 604800L);
        time(START); jwt.validateConfiguration();
    }
    private void time(Instant at) { ReflectionTestUtils.setField(jwt, "clock", Clock.fixed(at, ZoneOffset.UTC)); }
    private String login() { return jwt.generateTokenWithTenantId(user, "user", 10L, 20L, 3); }
    @Test void first_token_lasts_seven_days() {
        String token = login();
        assertThat(jwt.extractExpiration(token).toInstant()).isEqualTo(START.plus(Duration.ofDays(7)));
    }
    @Test void repeated_renewals_are_capped_at_first_login_plus_180_days() {
        String token = login(); String sid = jwt.extractSessionId(token);
        for (int day = 6; day <= 174; day += 6) {
            time(START.plus(Duration.ofDays(day))); token = jwt.renewSessionToken(token, sid);
            assertThat(jwt.extractSessionId(token)).isEqualTo(sid);
            assertThat(jwt.extractTenantId(token)).isEqualTo(10L);
        }
        time(START.plus(Duration.ofDays(179))); token = jwt.renewSessionToken(token, sid);
        assertThat(jwt.extractExpiration(token).toInstant()).isEqualTo(START.plus(Duration.ofDays(180)));
        String finalToken = token; time(START.plus(Duration.ofDays(180)).plusSeconds(1));
        assertThatThrownBy(() -> jwt.renewSessionToken(finalToken, sid)).isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class);
    }
    @Test void expired_token_cannot_be_revived_even_inside_180_days() {
        String token = login(); time(START.plus(Duration.ofDays(8)));
        assertThatThrownBy(() -> jwt.renewSessionToken(token, "sid")).isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class);
    }
    @Test void token_at_exact_expiry_cannot_renew() {
        String token = login(); String sid = jwt.extractSessionId(token);
        time(START.plus(Duration.ofDays(7)));
        assertThatThrownBy(() -> jwt.renewSessionToken(token, sid))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void concurrent_renewals_keep_one_identity_and_deadline() {
        String token = login(); String sid = jwt.extractSessionId(token);
        time(START.plus(Duration.ofDays(6)));
        String first = jwt.renewSessionToken(token, sid);
        String second = jwt.renewSessionToken(token, sid);
        assertThat(first).isNotEqualTo(second);
        assertThat(jwt.extractSessionId(first)).isEqualTo(jwt.extractSessionId(second));
        assertThat(jwt.extractExpiration(first)).isEqualTo(jwt.extractExpiration(second));
    }
    @Test void context_change_keeps_absolute_deadline_and_new_context() {
        String token = login();
        for (int day = 6; day <= 174; day += 6) {
            time(START.plus(Duration.ofDays(day))); token = jwt.renewSessionToken(token, jwt.extractSessionId(token));
        }
        time(START.plus(Duration.ofDays(179)));
        String switched = jwt.inheritSessionLifetime(jwt.generateTokenWithTenantId(user, "user", 99L), token);
        assertThat(jwt.extractTenantId(switched)).isEqualTo(99L);
        assertThat(jwt.extractExpiration(switched).toInstant()).isEqualTo(START.plus(Duration.ofDays(180)));
    }
    @Test void revoke_invalidates_original_and_renewed_tokens_without_new_rows() {
        UserSessionMapper mapper = mock(UserSessionMapper.class);
        SessionManagementServiceImpl sessions = new SessionManagementServiceImpl(mapper);
        ReflectionTestUtils.setField(sessions, "jwtUtil", jwt);
        String token = login(); String sid = jwt.extractSessionId(token);
        UserSession row = new UserSession(); row.setPid(sid); row.setId(5L); row.setUserId(1L);
        when(mapper.findByPid(sid)).thenReturn(row);
        when(mapper.revokeSession(5L)).thenAnswer(invocation -> { row.setRevoked(true); return 1; });
        time(START.plus(Duration.ofDays(6))); String renewed = jwt.renewSessionToken(token, sid);
        assertThat(sessions.isSessionValid(token)).isTrue(); assertThat(sessions.isSessionValid(renewed)).isTrue();
        sessions.revokeSessionByToken(renewed);
        assertThat(sessions.isSessionValid(token)).isFalse(); assertThat(sessions.isSessionValid(renewed)).isFalse();
        verify(mapper, never()).insertIfAbsent(any());
    }
}
