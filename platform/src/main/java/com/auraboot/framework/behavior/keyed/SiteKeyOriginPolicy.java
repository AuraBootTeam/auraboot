package com.auraboot.framework.behavior.keyed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Enforces a site key's {@code origin_allowlist} for the public keyed-collect path (SP2).
 *
 * <p>SP1 stored the allowlist (store-only); SP2 enforces it. An empty/unset allowlist means
 * "not configured" → open (recorded by the caller), matching GA-style public collection where
 * most keys do not pin origins. The lookup mirrors {@code SiteKeyRegistry}'s hot path: a
 * cross-tenant read of the active key's allowlist, cached.
 *
 * <p>No self-heal: a configured allowlist that the request origin is not in resolves to a
 * rejection at the caller ({@link KeyedCollectGuard}); this class only answers allowed/not.
 */
@Slf4j
@Service
public class SiteKeyOriginPolicy {

    private static final String TABLE = "mt_behavior_site_key";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** Cache of active site_key -> parsed allowlist. Mirrors the registry hot path. */
    private final Cache<String, List<String>> allowlistByKey = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    public SiteKeyOriginPolicy(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** Pure: empty/null allowlist = open; otherwise the origin must be an exact allowlist entry. */
    public static boolean originMatches(String origin, List<String> allowlist) {
        if (allowlist == null || allowlist.isEmpty()) {
            return true;
        }
        return origin != null && allowlist.contains(origin);
    }

    /**
     * @param siteKey the active public key
     * @param origin  the request {@code Origin} (or {@code Referer}) header
     * @return true if the key has no allowlist or the origin is on it
     */
    public boolean isOriginAllowed(String siteKey, String origin) {
        return originMatches(origin, loadAllowlist(siteKey));
    }

    private List<String> loadAllowlist(String siteKey) {
        List<String> cached = allowlistByKey.getIfPresent(siteKey);
        if (cached != null) {
            return cached;
        }
        List<String> allow = List.of();
        try {
            String json = jdbcTemplate.queryForObject(
                    "SELECT origin_allowlist FROM " + TABLE + " WHERE site_key = ? AND status = 'active' LIMIT 1",
                    String.class, siteKey);
            if (json != null && !json.isBlank()) {
                allow = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            }
        } catch (EmptyResultDataAccessException ignored) {
            // unknown/disabled key — origin check is moot (the registry rejects first); keep open
        } catch (Exception e) {
            log.warn("Failed to parse origin_allowlist for a site key: {}", e.getMessage());
        }
        allowlistByKey.put(siteKey, allow);
        return allow;
    }
}
