package com.auraboot.framework.application.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.cache.Cache;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Receive side of the cache eviction broadcast: drops the evicted entries from
 * this instance's local caches. Evictions go through the receive-side methods
 * so they never re-publish. Unknown cache names are ignored — regions are
 * created lazily per instance and a remote region this instance never touched
 * has nothing to drop.
 */
@Slf4j
@Component
@ConditionalOnExpression("!'${spring.data.redis.host:}'.isEmpty()")
@RequiredArgsConstructor
public class CacheEvictRemoteSubscriber implements MessageListener {

    private final List<BroadcastableCaffeineCacheManager> cacheManagers;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            CacheEvictMessage msg = objectMapper.readValue(message.getBody(), CacheEvictMessage.class);
            for (BroadcastableCaffeineCacheManager cacheManager : cacheManagers) {
                Cache cache = cacheManager.getCache(msg.cache());
                if (cache == null) {
                    continue;
                }
                if (cache instanceof BroadcastingCache broadcasting) {
                    if (msg.all()) {
                        broadcasting.clearRemotely();
                    } else {
                        broadcasting.evictRemotely(msg.key());
                    }
                } else {
                    if (msg.all()) {
                        cache.clear();
                    } else {
                        cache.evict(msg.key());
                    }
                }
            }
            log.debug("Cache broadcast: applied eviction for cache={} key={} all={}",
                msg.cache(), msg.key(), msg.all());
        } catch (Exception e) {
            log.warn("Cache broadcast: failed to process eviction message: {}", e.getMessage());
        }
    }
}
