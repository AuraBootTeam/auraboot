package com.auraboot.framework.agent.crosstenant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JdbcTemplate + Caffeine implementation of {@link CrossTenantAclService}.
 *
 * <p>Cache strategy (per spec §"ACL 缓存"):
 * <ul>
 *   <li>{@code maximumSize=10_000} — order-of-magnitude bound; cross-tenant
 *       grants are rare relative to user actions, so a small cache fits
 *       happy-path traffic without eviction churn.</li>
 *   <li>{@code expireAfterWrite=10s} — owner-accepted upper bound on
 *       revoke/expire propagation; the admin controller also calls
 *       {@link #invalidate} after grant/revoke writes so the typical case
 *       sees instant propagation, with the TTL acting as a backstop for
 *       missed invalidations (cluster nodes that didn't receive the
 *       admin-write event, etc.).</li>
 * </ul>
 *
 * <p>Note: there is currently no listener-based cluster-wide invalidation —
 * each node holds its own Caffeine. A multi-node deployment can therefore see
 * up to 10s of stale "allowed" answers on a peer node. That is the
 * acceptable trade-off documented in the spec; if owner asks for stricter
 * propagation the upgrade path is to publish an {@code GrantInvalidatedEvent}
 * here and have all peers listen via the existing distributed event bus.
 */
@Slf4j
@Service
public class CrossTenantAclServiceImpl implements CrossTenantAclService {

    private static final String SELECT_ACTIVE_GRANT_SQL =
            "SELECT id, expires_at, revoked_at " +
                    "FROM ab_cross_tenant_grant " +
                    "WHERE parent_tenant_id = ? " +
                    "  AND child_tenant_id = ? " +
                    "  AND grant_type = ? " +
                    "  AND revoked_at IS NULL " +
                    "ORDER BY granted_at DESC " +
                    "LIMIT 1";

    /**
     * Sentinel grantId returned from {@link CrossTenantDecision#allowed(Long)}
     * for the same-tenant short-circuit (where there is no row in
     * ab_cross_tenant_grant — same-tenant spawn never needs a grant).
     */
    private static final Long SAME_TENANT_GRANT_ID = null;

    private final JdbcTemplate jdbc;
    private final boolean featureEnabled;
    private final Cache<CacheKey, CrossTenantDecision> cache;

    public CrossTenantAclServiceImpl(
            JdbcTemplate jdbc,
            @Value("${aura.security.cross-tenant.enabled:true}") boolean featureEnabled) {
        this.jdbc = jdbc;
        this.featureEnabled = featureEnabled;
        this.cache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(10))
                .build();
        log.info("CrossTenantAclService initialised: featureEnabled={}, cache=Caffeine(max=10000, ttl=10s)",
                featureEnabled);
    }

    @Override
    public boolean allows(Long parentTenantId, Long childTenantId, String grantType) {
        return evaluate(parentTenantId, childTenantId, grantType).isAllowed();
    }

    @Override
    public CrossTenantDecision evaluate(Long parentTenantId, Long childTenantId, String grantType) {
        if (parentTenantId == null || childTenantId == null || grantType == null || grantType.isBlank()) {
            return CrossTenantDecision.denied(
                    CrossTenantDecision.DENIED_NO_GRANT,
                    "null parent/child tenant or blank grantType");
        }

        // Same-tenant short-circuit — no grant row required.
        if (Objects.equals(parentTenantId, childTenantId)) {
            return CrossTenantDecision.allowed(SAME_TENANT_GRANT_ID);
        }

        if (!featureEnabled) {
            return CrossTenantDecision.denied(
                    CrossTenantDecision.DENIED_FEATURE_DISABLED,
                    "aura.security.cross-tenant.enabled=false");
        }

        CacheKey key = new CacheKey(parentTenantId, childTenantId, grantType);
        return cache.get(key, this::loadFromDb);
    }

    @Override
    public void invalidate(Long parentTenantId, Long childTenantId, String grantType) {
        if (parentTenantId == null || childTenantId == null || grantType == null) {
            return;
        }
        cache.invalidate(new CacheKey(parentTenantId, childTenantId, grantType));
    }

    /**
     * Cache loader. Called by Caffeine on miss only — same-tenant and
     * feature-flag branches above never reach here.
     */
    private CrossTenantDecision loadFromDb(CacheKey key) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                SELECT_ACTIVE_GRANT_SQL,
                key.parentTenantId, key.childTenantId, key.grantType);
        if (rows.isEmpty()) {
            return CrossTenantDecision.denied(
                    CrossTenantDecision.DENIED_NO_GRANT,
                    "no active grant row for ("
                            + key.parentTenantId + " → " + key.childTenantId
                            + ", " + key.grantType + ")");
        }
        Map<String, Object> row = rows.get(0);
        Long grantId = ((Number) row.get("id")).longValue();
        Object expiresRaw = row.get("expires_at");
        if (expiresRaw != null) {
            Instant expiresAt = ((java.sql.Timestamp) expiresRaw).toInstant();
            if (!expiresAt.isAfter(Instant.now())) {
                return CrossTenantDecision.denied(
                        CrossTenantDecision.DENIED_EXPIRED,
                        "grant " + grantId + " expired at " + expiresAt);
            }
        }
        // revoked_at IS NULL is enforced in the WHERE clause; reaching here
        // means the row is active.
        return CrossTenantDecision.allowed(grantId);
    }

    /**
     * Tuple cache key — equals/hashCode by all 3 fields. Final immutable
     * record so Caffeine can hash it cheaply.
     */
    private record CacheKey(Long parentTenantId, Long childTenantId, String grantType) {
    }
}
