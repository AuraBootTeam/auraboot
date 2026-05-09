package com.auraboot.framework.lock;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LockConfigurationTest {

    @Test
    void redisDistributedLock_returnsRedisImpl() {
        LockConfiguration cfg = new LockConfiguration();
        DistributedLock lock = cfg.redisDistributedLock(mock(StringRedisTemplate.class));
        assertThat(lock).isInstanceOf(RedisDistributedLock.class);
    }

    @Test
    void localDistributedLock_returnsLocalImpl() {
        LockConfiguration cfg = new LockConfiguration();
        DistributedLock lock = cfg.localDistributedLock();
        assertThat(lock).isInstanceOf(LocalDistributedLock.class);
    }
}
