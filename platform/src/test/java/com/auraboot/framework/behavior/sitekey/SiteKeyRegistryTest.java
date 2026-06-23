package com.auraboot.framework.behavior.sitekey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SiteKeyRegistryTest {

    private JdbcTemplate jdbcTemplate;
    private SiteKeyRegistry registry;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        registry = new SiteKeyRegistry(jdbcTemplate);
    }

    @Test
    @DisplayName("resolveTenant returns owning tenant for an active key")
    void resolveActiveKey() {
        when(jdbcTemplate.queryForObject(contains("WHERE site_key = ? AND status = 'active'"),
                eq(Long.class), eq("abk_active"))).thenReturn(42L);

        assertThat(registry.resolveTenant("abk_active")).contains(42L);
    }

    @Test
    @DisplayName("resolveTenant caches the positive result — second call hits no DB")
    void resolveCachesPositive() {
        when(jdbcTemplate.queryForObject(contains("status = 'active'"), eq(Long.class), eq("abk_cache")))
                .thenReturn(7L);

        assertThat(registry.resolveTenant("abk_cache")).contains(7L);
        assertThat(registry.resolveTenant("abk_cache")).contains(7L);

        verify(jdbcTemplate, times(1))
                .queryForObject(contains("status = 'active'"), eq(Long.class), eq("abk_cache"));
    }

    @Test
    @DisplayName("resolveTenant returns empty for an unknown/disabled key")
    void resolveUnknownKey() {
        when(jdbcTemplate.queryForObject(contains("status = 'active'"), eq(Long.class), eq("abk_missing")))
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(registry.resolveTenant("abk_missing")).isEmpty();
    }

    @Test
    @DisplayName("resolveTenant short-circuits null/blank without querying")
    void resolveBlankKey() {
        assertThat(registry.resolveTenant(null)).isEmpty();
        assertThat(registry.resolveTenant("  ")).isEmpty();
        verify(jdbcTemplate, never()).queryForObject(contains("status"), eq(Long.class), eq(null));
    }

    @Test
    @DisplayName("evict forces the next resolve to re-query the DB")
    void evictReQueries() {
        when(jdbcTemplate.queryForObject(contains("status = 'active'"), eq(Long.class), eq("abk_evict")))
                .thenReturn(9L)
                .thenThrow(new EmptyResultDataAccessException(1));

        assertThat(registry.resolveTenant("abk_evict")).contains(9L);
        registry.evict("abk_evict");
        // After eviction, the cache no longer answers, so the (now disabled) key resolves empty.
        assertThat(registry.resolveTenant("abk_evict")).isEmpty();
        verify(jdbcTemplate, times(2))
                .queryForObject(contains("status = 'active'"), eq(Long.class), eq("abk_evict"));
    }

    @Test
    @DisplayName("existsAnyTenant reports cross-tenant presence")
    void existsAnyTenant() {
        when(jdbcTemplate.queryForObject(contains("count(1)"), eq(Integer.class), eq("abk_dup")))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("count(1)"), eq(Integer.class), eq("abk_free")))
                .thenReturn(0);

        assertThat(registry.existsAnyTenant("abk_dup")).isTrue();
        assertThat(registry.existsAnyTenant("abk_free")).isFalse();
    }
}
