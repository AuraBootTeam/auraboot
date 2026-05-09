package com.auraboot.framework.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisDistributedLockTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private RedisDistributedLock lock;

    @BeforeEach
    void setUp() {
        lock = new RedisDistributedLock(redisTemplate);
    }

    private void stubAcquire(boolean ok) {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(ok);
    }

    @Test
    void tryLock_success_recordsHolder() {
        stubAcquire(true);
        assertThat(lock.tryLock("k", 1, TimeUnit.SECONDS)).isTrue();
        assertThat(lock.isHeldByCurrentThread("k")).isTrue();
    }

    @Test
    void tryLock_failure_returnsFalse() {
        stubAcquire(false);
        assertThat(lock.tryLock("k", 1, TimeUnit.SECONDS)).isFalse();
        assertThat(lock.isHeldByCurrentThread("k")).isFalse();
    }

    @Test
    void tryLock_redisException_returnsFalse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));
        assertThat(lock.tryLock("k", 1, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void tryLock_zeroTimeout_usesDefaultLease() {
        stubAcquire(true);
        assertThat(lock.tryLock("k", 0, TimeUnit.SECONDS)).isTrue();
        verify(valueOps).setIfAbsent(anyString(), anyString(), eq(Duration.ofMinutes(3)));
    }

    @Test
    void unlock_held_executesScript() {
        stubAcquire(true);
        lock.tryLock("k", 1, TimeUnit.SECONDS);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(1L);

        lock.unlock("k");
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), any());
        assertThat(lock.isHeldByCurrentThread("k")).isFalse();
    }

    @Test
    void unlock_alreadyReleased_clearsLocalState() {
        stubAcquire(true);
        lock.tryLock("k", 1, TimeUnit.SECONDS);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(0L);
        lock.unlock("k");
        assertThat(lock.isHeldByCurrentThread("k")).isFalse();
    }

    @Test
    void unlock_notHeld_noOp() {
        lock.unlock("nope");
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void unlock_redisException_swallowed() {
        stubAcquire(true);
        lock.tryLock("k", 1, TimeUnit.SECONDS);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RuntimeException("redis"));
        lock.unlock("k"); // No throw
    }

    @Test
    void renewLease_held_returnsTrue() {
        stubAcquire(true);
        lock.tryLock("k", 1, TimeUnit.SECONDS);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(1L);

        assertThat(lock.renewLease("k", 60, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void renewLease_redisReturnsZero_returnsFalse() {
        stubAcquire(true);
        lock.tryLock("k", 1, TimeUnit.SECONDS);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(0L);

        assertThat(lock.renewLease("k", 60, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void renewLease_notHeld_returnsFalse() {
        assertThat(lock.renewLease("nope", 60, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void renewLease_redisException_returnsFalse() {
        stubAcquire(true);
        lock.tryLock("k", 1, TimeUnit.SECONDS);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RuntimeException("redis"));
        assertThat(lock.renewLease("k", 60, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void lock_blocking_acquiresWhenAvailable() throws Exception {
        stubAcquire(true);
        lock.lock("k", 1, TimeUnit.SECONDS);
        assertThat(lock.isHeldByCurrentThread("k")).isTrue();
    }

    @Test
    void lock_blocking_timeout_throwsBusinessException() {
        stubAcquire(false);
        try {
            lock.lock("k", 50, TimeUnit.MILLISECONDS);
            org.junit.jupiter.api.Assertions.fail("expected exception");
        } catch (InterruptedException e) {
            org.junit.jupiter.api.Assertions.fail("unexpected " + e);
        } catch (com.auraboot.framework.exception.BusinessException expected) {
            assertThat(expected.getMessage()).contains("Failed to acquire lock");
        }
    }
}
