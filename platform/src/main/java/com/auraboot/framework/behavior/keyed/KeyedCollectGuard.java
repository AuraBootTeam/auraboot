package com.auraboot.framework.behavior.keyed;

import com.auraboot.framework.auth.service.ApiRateLimiter;
import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.sitekey.SiteKeyRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Ordered abuse-protection chain for the public keyed-collect endpoint (SP2). Runs
 * cheapest-first: key resolve → origin allowlist → per-key/per-IP rate limit → batch cap. Any
 * failure throws a {@link ResponseStatusException} with a stable reason code. No self-heal: a
 * failed check rejects — it never creates a key, relaxes a limit, or falls back.
 *
 * <p>Thresholds are injected ({@code behavior.collect.keyed.*}) so an operator (or a test) can
 * tune them without code change. The rate limiter is the platform's in-process sliding window
 * (also used by login); distributed Redis-backed limiting is a hardening follow-up.
 */
@Component
public class KeyedCollectGuard {

    private final SiteKeyRegistry registry;
    private final SiteKeyOriginPolicy originPolicy;
    private final ApiRateLimiter rateLimiter;
    private final int maxPerKey;
    private final int maxPerIp;
    private final int maxBatch;

    public KeyedCollectGuard(SiteKeyRegistry registry,
                             SiteKeyOriginPolicy originPolicy,
                             ApiRateLimiter rateLimiter,
                             @Value("${behavior.collect.keyed.max-per-key-per-min:600}") int maxPerKey,
                             @Value("${behavior.collect.keyed.max-per-ip-per-min:300}") int maxPerIp,
                             @Value("${behavior.collect.keyed.max-batch:50}") int maxBatch) {
        this.registry = registry;
        this.originPolicy = originPolicy;
        this.rateLimiter = rateLimiter;
        this.maxPerKey = maxPerKey;
        this.maxPerIp = maxPerIp;
        this.maxBatch = maxBatch;
    }

    /**
     * Run the protection chain.
     *
     * @return the tenant the key resolves to
     * @throws ResponseStatusException 403 {@code site_key_invalid} / 403 {@code origin_not_allowed}
     *                                 / 429 {@code rate_limited} / 400 {@code batch_too_large}
     */
    public long check(String siteKey, String origin, String clientIp, List<BehaviorEventInput> events) {
        if (siteKey == null || siteKey.isBlank()) {
            throw reject(HttpStatus.FORBIDDEN, "site_key_invalid");
        }
        Long tenantId = registry.resolveTenant(siteKey).orElse(null);
        if (tenantId == null) {
            throw reject(HttpStatus.FORBIDDEN, "site_key_invalid");
        }
        if (!originPolicy.isOriginAllowed(siteKey, origin)) {
            throw reject(HttpStatus.FORBIDDEN, "origin_not_allowed");
        }
        if (!rateLimiter.isAllowed("collect:key:" + siteKey, maxPerKey)) {
            throw reject(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
        }
        if (clientIp != null && !rateLimiter.isAllowed("collect:ip:" + clientIp, maxPerIp)) {
            throw reject(HttpStatus.TOO_MANY_REQUESTS, "rate_limited");
        }
        if (events != null && events.size() > maxBatch) {
            throw reject(HttpStatus.BAD_REQUEST, "batch_too_large");
        }
        return tenantId;
    }

    private ResponseStatusException reject(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }
}
