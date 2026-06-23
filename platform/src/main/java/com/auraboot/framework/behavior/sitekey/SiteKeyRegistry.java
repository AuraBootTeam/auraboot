package com.auraboot.framework.behavior.sitekey;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Authoritative resolver from a public site key to its owning tenant.
 *
 * <p>This is the contract SP2 (anonymous {@code /api/collect}) consumes: an
 * unauthenticated request carries a public {@code abk_} key, and the server —
 * never the client — decides which tenant the event belongs to. Resolution is a
 * hot path, so active keys are cached; {@link #disableAndEvict}/{@link #evict}
 * drop a key the moment it is disabled.
 *
 * <p>Lookup is intentionally <b>cross-tenant</b> (a public request has no tenant
 * context yet), so it reads {@code mt_behavior_site_key} directly via
 * {@link JdbcTemplate} rather than the tenant-scoped dynamic-data service. There
 * is no self-heal / fallback: an unknown or disabled key resolves to
 * {@link Optional#empty()} and the caller decides (SP2 rejects).
 */
@Slf4j
@Service
public class SiteKeyRegistry {

    /** Physical table for the {@code behavior_site_key} dynamic model. */
    static final String TABLE = "mt_behavior_site_key";

    private final JdbcTemplate jdbcTemplate;

    /** Positive cache: active site_key -> tenant_id. Disabled/unknown keys are not cached. */
    private final Cache<String, Long> tenantByKey = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    public SiteKeyRegistry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Resolve an active site key to its owning tenant id.
     *
     * @param siteKey the public {@code abk_} key (from an embedded app)
     * @return the owning tenant id, or empty if the key is unknown or disabled
     */
    public Optional<Long> resolveTenant(String siteKey) {
        if (siteKey == null || siteKey.isBlank()) {
            return Optional.empty();
        }
        Long cached = tenantByKey.getIfPresent(siteKey);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            Long tenantId = jdbcTemplate.queryForObject(
                    "SELECT tenant_id FROM " + TABLE + " WHERE site_key = ? AND status = 'active' LIMIT 1",
                    Long.class, siteKey);
            if (tenantId != null) {
                tenantByKey.put(siteKey, tenantId);
                return Optional.of(tenantId);
            }
            return Optional.empty();
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether the given key already exists for <b>any</b> tenant. Used by the
     * create handler to guarantee global uniqueness before insert (the column's
     * unique index is only per-tenant).
     *
     * @param siteKey candidate key
     * @return true if a row with this {@code site_key} exists in any tenant
     */
    public boolean existsAnyTenant(String siteKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(1) FROM " + TABLE + " WHERE site_key = ?",
                Integer.class, siteKey);
        return count != null && count > 0;
    }

    /**
     * Drop a key from the resolver cache (call after disabling it).
     *
     * @param siteKey the key to evict (no-op if null/blank)
     */
    public void evict(String siteKey) {
        if (siteKey != null && !siteKey.isBlank()) {
            tenantByKey.invalidate(siteKey);
        }
    }
}
