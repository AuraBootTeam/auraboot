package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.permission.mapper.RecordShareMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Necessary-condition memo for the record-share fast reject: "does this record carry ANY
 * active share at all". The per-subject authorization queries stay uncached, so a revoked
 * share still denies on the very next check; only a freshly GRANTED share can take up to
 * the TTL to become visible, and share mutations evict explicitly. Request paths call this
 * once per (tenant, resource, record pid) instead of once per evaluator pass — a list page
 * whose permission closure re-checks the same rows evaluates hundreds of share checks.
 */
@Component
public class RecordSharePresenceCache {

    private static final Duration PRESENCE_TTL = Duration.ofSeconds(30);

    private final RecordShareMapper recordShareMapper;
    private final Cache<String, Boolean> presence = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(PRESENCE_TTL)
            .build();

    public RecordSharePresenceCache(RecordShareMapper recordShareMapper) {
        this.recordShareMapper = recordShareMapper;
    }

    public boolean hasActiveShares(Long tenantId, String resourceCode, String recordPid, Instant now) {
        String key = tenantId + ":" + resourceCode + ":" + recordPid;
        Boolean cached = presence.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        boolean present = recordShareMapper.hasActiveSharesByRecordPid(
                tenantId, resourceCode, recordPid, now);
        presence.put(key, present);
        return present;
    }

    public void evictRecord(Long tenantId, String resourceCode, String recordPid) {
        presence.invalidate(tenantId + ":" + resourceCode + ":" + recordPid);
    }
}
