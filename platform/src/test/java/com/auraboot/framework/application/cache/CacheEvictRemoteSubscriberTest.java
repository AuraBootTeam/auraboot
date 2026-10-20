package com.auraboot.framework.application.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CacheEvictRemoteSubscriberTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<CacheEvictMessage> published = new CopyOnWriteArrayList<>();

    private record StringMessage(String channel, byte[] body) implements Message {
        @Override
        public byte[] getBody() {
            return body;
        }

        @Override
        public byte[] getChannel() {
            return channel.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static Message message(String channel, byte[] body) {
        return new StringMessage(channel, body);
    }

    private BroadcastableCaffeineCacheManager managerWithRegions(String... names) {
        BroadcastableCaffeineCacheManager manager = new BroadcastableCaffeineCacheManager(published::add);
        manager.setCacheNames(List.of(names));
        return manager;
    }

    private CacheEvictRemoteSubscriber subscriber(BroadcastableCaffeineCacheManager... managers) {
        return new CacheEvictRemoteSubscriber(List.of(managers), objectMapper);
    }

    private static byte[] json(ObjectMapper mapper, CacheEvictMessage message) {
        try {
            return mapper.writeValueAsString(message).getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void keyedEvictionDropsOnlyThatEntryWithoutRepublishing() throws Exception {
        BroadcastableCaffeineCacheManager manager = managerWithRegions("dictData");
        manager.getCache("dictData").put("gender", "G");
        manager.getCache("dictData").put("status", "S");

        subscriber(manager).onMessage(
            message("cache-evict", json(objectMapper, new CacheEvictMessage("dictData", "gender", false))),
            new byte[0]);

        assertThat(manager.getCache("dictData").get("gender")).isNull();
        assertThat(manager.getCache("dictData").get("status")).isNotNull();
        assertThat(published).isEmpty();
    }

    @Test
    void regionWideEvictionClearsEverything() throws Exception {
        BroadcastableCaffeineCacheManager manager = managerWithRegions("user-permissions");
        manager.getCache("user-permissions").put("tenant:1:9", "snap");

        subscriber(manager).onMessage(
            message("cache-evict", json(objectMapper, new CacheEvictMessage("user-permissions", null, true))),
            new byte[0]);

        assertThat(manager.getCache("user-permissions").get("tenant:1:9")).isNull();
        assertThat(published).isEmpty();
    }

    @Test
    void unknownCacheNameAndMalformedPayloadAreIgnored() throws Exception {
        BroadcastableCaffeineCacheManager manager = managerWithRegions("dictData");

        assertThatCode(() -> subscriber(manager).onMessage(
            message("cache-evict", json(objectMapper, new CacheEvictMessage("no-such-cache", "k", false))),
            new byte[0])).doesNotThrowAnyException();

        assertThatCode(() -> subscriber(manager).onMessage(
            message("cache-evict", "not-json".getBytes(StandardCharsets.UTF_8)),
            new byte[0])).doesNotThrowAnyException();

        assertThat(manager.getCache("dictData").get("k")).isNull();
    }
}
