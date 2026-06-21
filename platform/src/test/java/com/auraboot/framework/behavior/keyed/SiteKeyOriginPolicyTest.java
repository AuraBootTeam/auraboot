package com.auraboot.framework.behavior.keyed;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link SiteKeyOriginPolicy#originMatches} — the pure origin-allowlist decision.
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
}
