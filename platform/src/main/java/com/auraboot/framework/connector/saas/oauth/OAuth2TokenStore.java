package com.auraboot.framework.connector.saas.oauth;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.connector.saas.SaasConnectorConfig;
import com.auraboot.framework.connector.saas.oauth.mapper.ConnectorOAuthTokenMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persistent OAuth2 token store with concurrent-safe refresh. PRD 18 §B.3.2.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Persist {@code (tenant, vendor) → (access, refresh, expiresAt)} rows
 *       in {@code connector_oauth_token}, with access + refresh tokens
 *       encrypted by {@link FieldEncryptionService}.</li>
 *   <li>{@link #getValidAccessToken} returns a non-expired access token,
 *       triggering refresh when within {@link #REFRESH_LEAD_SECONDS} of
 *       expiry. Concurrent callers for the same {@code (tenant, vendor)}
 *       are serialised through a per-key {@link ReentrantLock} so only ONE
 *       refresh fires; the other callers wait for it to complete and read
 *       the new row.</li>
 *   <li>{@link #persistInitial} seats the row after the initial OAuth
 *       code-grant exchange. Idempotent UPSERT semantics — running the OAuth
 *       flow twice does not duplicate the row.</li>
 *   <li>{@link #revoke} deletes the row; intended for "Disconnect Connector"
 *       UI affordance.</li>
 * </ul>
 *
 * <p>The refresh wire shape is per-vendor; resolution happens by
 * {@link TokenRefresher#vendor()} matching {@code config.vendor()}. If no
 * refresher is registered, the store assumes the connector uses static API
 * keys (Stripe, DingTalk inner-app) and {@code getValidAccessToken} returns
 * the stored access token without refresh.
 */
@Slf4j
@Service
public class OAuth2TokenStore {

    /** Refresh when the access token has fewer than this many seconds left. */
    static final long REFRESH_LEAD_SECONDS = 60L;

    private final ConnectorOAuthTokenMapper mapper;
    private final FieldEncryptionService encryption;
    private final Map<String, TokenRefresher> refreshers;
    private final Clock clock;

    /**
     * Per-key locks. Bounded by the number of distinct (tenant, vendor)
     * pairs in use — small even for large tenants.
     */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Autowired
    public OAuth2TokenStore(ConnectorOAuthTokenMapper mapper,
                            FieldEncryptionService encryption,
                            List<TokenRefresher> refreshers) {
        this(mapper, encryption, refreshers, Clock.systemUTC());
    }

    /** Test seam — inject a fixed clock. */
    OAuth2TokenStore(ConnectorOAuthTokenMapper mapper,
                     FieldEncryptionService encryption,
                     List<TokenRefresher> refreshers,
                     Clock clock) {
        this.mapper = Objects.requireNonNull(mapper);
        this.encryption = Objects.requireNonNull(encryption);
        this.clock = Objects.requireNonNull(clock);
        java.util.Map<String, TokenRefresher> map = new java.util.HashMap<>();
        if (refreshers != null) {
            for (TokenRefresher r : refreshers) map.put(r.vendor(), r);
        }
        this.refreshers = Map.copyOf(map);
    }

    /**
     * Get a usable access token, refreshing if within
     * {@link #REFRESH_LEAD_SECONDS} of expiry.
     *
     * @throws TokenRefreshException when refresh is needed but no
     *         {@link TokenRefresher} is registered for the vendor OR the
     *         refresh attempt fails.
     */
    public String getValidAccessToken(Long tenantId, SaasConnectorConfig config) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(config, "config");
        String vendor = config.vendor();
        ConnectorOAuthToken row = mapper.findByTenantAndVendor(tenantId, vendor);
        if (row == null) {
            throw new TokenRefreshException(
                    "No OAuth row for tenant=" + tenantId + " vendor=" + vendor
                            + " — initial OAuth code-grant must be completed first");
        }
        if (!needsRefresh(row)) {
            return encryption.decrypt(row.getAccessToken());
        }
        return refreshUnderLock(tenantId, vendor, config);
    }

    /** Whether the current row is within REFRESH_LEAD_SECONDS of expiry. */
    boolean needsRefresh(ConnectorOAuthToken row) {
        if (row == null || row.getExpiresAt() == null) return true;
        Instant now = clock.instant();
        Instant deadline = row.getExpiresAt().minus(Duration.ofSeconds(REFRESH_LEAD_SECONDS));
        return !now.isBefore(deadline);
    }

    private String refreshUnderLock(Long tenantId, String vendor, SaasConnectorConfig config) {
        ReentrantLock lock = locks.computeIfAbsent(key(tenantId, vendor), k -> new ReentrantLock());
        lock.lock();
        try {
            // Re-read under lock — a concurrent caller may have already refreshed.
            ConnectorOAuthToken latest = mapper.findByTenantAndVendor(tenantId, vendor);
            if (latest == null) {
                throw new TokenRefreshException(
                        "Row vanished mid-refresh for tenant=" + tenantId + " vendor=" + vendor);
            }
            if (!needsRefresh(latest)) {
                return encryption.decrypt(latest.getAccessToken());
            }
            TokenRefresher refresher = refreshers.get(vendor);
            if (refresher == null) {
                throw new TokenRefreshException(
                        "No TokenRefresher registered for vendor=" + vendor
                                + " (API-key flows should not call getValidAccessToken)");
            }
            String currentRefresh = latest.getRefreshToken() != null
                    ? encryption.decrypt(latest.getRefreshToken()) : null;
            TokenRefresher.RefreshedToken fresh = refresher.refresh(config, currentRefresh);
            persistRefresh(tenantId, vendor, fresh);
            log.info("OAuth refresh OK tenant={} vendor={} expiresAt={}",
                    tenantId, vendor, fresh.expiresAt());
            return fresh.accessToken();
        } finally {
            lock.unlock();
        }
    }

    /**
     * UPSERT the (tenant, vendor) row from the initial OAuth code-grant
     * exchange or an out-of-band refresh.
     */
    @Transactional
    public ConnectorOAuthToken persistInitial(Long tenantId, String vendor,
                                              TokenRefresher.RefreshedToken token) {
        ConnectorOAuthToken existing = mapper.findByTenantAndVendor(tenantId, vendor);
        if (existing != null) {
            mapper.updateTokens(tenantId, vendor,
                    encryption.encrypt(token.accessToken()),
                    token.refreshToken() != null ? encryption.encrypt(token.refreshToken()) : null,
                    token.expiresAt(),
                    String.join(",", token.scopes()));
            return mapper.findByTenantAndVendor(tenantId, vendor);
        }
        ConnectorOAuthToken row = new ConnectorOAuthToken();
        row.setPid(UlidGenerator.generate());
        row.setTenantId(tenantId);
        row.setVendor(vendor);
        row.setAccessToken(encryption.encrypt(token.accessToken()));
        row.setRefreshToken(token.refreshToken() != null
                ? encryption.encrypt(token.refreshToken()) : null);
        row.setExpiresAt(token.expiresAt());
        row.setScopes(String.join(",", token.scopes()));
        mapper.insert(row);
        return row;
    }

    @Transactional
    public boolean revoke(Long tenantId, String vendor) {
        return mapper.deleteByTenantAndVendor(tenantId, vendor) > 0;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    void persistRefresh(Long tenantId, String vendor,
                        TokenRefresher.RefreshedToken token) {
        mapper.updateTokens(tenantId, vendor,
                encryption.encrypt(token.accessToken()),
                token.refreshToken() != null ? encryption.encrypt(token.refreshToken()) : null,
                token.expiresAt(),
                String.join(",", token.scopes()));
    }

    public Optional<ConnectorOAuthToken> find(Long tenantId, String vendor) {
        return Optional.ofNullable(mapper.findByTenantAndVendor(tenantId, vendor));
    }

    private static String key(Long tenantId, String vendor) {
        return tenantId + ":" + vendor;
    }
}
