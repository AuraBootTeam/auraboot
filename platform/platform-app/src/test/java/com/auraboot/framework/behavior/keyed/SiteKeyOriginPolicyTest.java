package com.auraboot.framework.behavior.keyed;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * Hermetic origin-policy decisions, parser failures, and cache boundaries.
 * The DB-load path ({@code isOriginAllowed}) is proven against real jsonb in {@code KeyedCollectIT}.
 */
class SiteKeyOriginPolicyTest {

    @Test
    void emptyOrNullAllowlist_meansOpen() {
        assertThat(SiteKeyOriginPolicy.originMatches("https://shop.acme.com", List.of())).isTrue();
        assertThat(SiteKeyOriginPolicy.originMatches("https://shop.acme.com", null)).isTrue();
    }

    @Test
    void matchesWhenOriginInAllowlist() {
        List<String> allow = List.of("https://shop.acme.com", "https://www.acme.com");
        assertThat(SiteKeyOriginPolicy.originMatches("https://shop.acme.com", allow)).isTrue();
    }

    @Test
    void rejectsWhenOriginNotInAllowlist() {
        List<String> allow = List.of("https://shop.acme.com");
        assertThat(SiteKeyOriginPolicy.originMatches("https://evil.example", allow)).isFalse();
    }

    @Test
    void rejectsNullOriginWhenAllowlistConfigured() {
        assertThat(SiteKeyOriginPolicy.originMatches(null, List.of("https://shop.acme.com"))).isFalse();
    }
    @Test
    void malformedJsonFailsClosedAndIsNotCachedAsOpen() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("abk_contract")))
                .thenReturn("{broken", "[\"https://shop.acme.com\"]");
        SiteKeyOriginPolicy policy = new SiteKeyOriginPolicy(jdbc, new ObjectMapper());
        assertThatThrownBy(() -> policy.isOriginAllowed("abk_contract", "https://evil.example"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("origin_policy_invalid");
        assertThat(policy.isOriginAllowed("abk_contract", "https://shop.acme.com")).isTrue();
        assertThat(policy.isOriginAllowed("abk_contract", "https://evil.example")).isFalse();
        verify(jdbc, times(2)).queryForObject(anyString(), eq(String.class), eq("abk_contract"));
    }

    @Test
    void objectRootCannotBecomeAnOpenAllowlist() {
        assertInvalidPolicy("{\"origins\":[\"https://shop.acme.com\"]}");
    }

    @Test
    void nonStringArrayMembersCannotBeCoercedIntoPolicy() {
        assertInvalidPolicy("[123]");
        assertInvalidPolicy("[null]");
    }

    @Test
    void unavailablePolicyStoreDoesNotBecomeOpen() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("abk_contract")))
                .thenThrow(new DataAccessResourceFailureException("synthetic unavailable store"));
        SiteKeyOriginPolicy policy = new SiteKeyOriginPolicy(jdbc, new ObjectMapper());
        assertThatThrownBy(() -> policy.isOriginAllowed("abk_contract", "https://evil.example"))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void disappearingOrDisabledKeyCannotBecomeAnOpenAllowlist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("abk_contract")))
                .thenThrow(new EmptyResultDataAccessException(1));
        SiteKeyOriginPolicy policy = new SiteKeyOriginPolicy(jdbc, new ObjectMapper());
        assertThatThrownBy(() -> policy.isOriginAllowed("abk_contract", "https://evil.example"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("site_key_invalid");
    }

    @Test
    void absentAndExplicitEmptyPoliciesRetainDocumentedOpenBehavior() {
        for (String json : new String[]{null, "", "[]"}) {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForObject(anyString(), eq(String.class), eq("abk_contract"))).thenReturn(json);
            SiteKeyOriginPolicy policy = new SiteKeyOriginPolicy(jdbc, new ObjectMapper());
            assertThat(policy.isOriginAllowed("abk_contract", "https://shop.acme.com")).isTrue();
        }
    }

    private static void assertInvalidPolicy(String json) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("abk_contract"))).thenReturn(json);
        SiteKeyOriginPolicy policy = new SiteKeyOriginPolicy(jdbc, new ObjectMapper());
        assertThatThrownBy(() -> policy.isOriginAllowed("abk_contract", "https://evil.example"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("origin_policy_invalid");
    }

}
