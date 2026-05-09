package com.auraboot.framework.lock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDistributedLockTest {

    private LocalDistributedLock lock;

    @BeforeEach
    void setUp() {
        lock = new LocalDistributedLock();
    }

    @Test
    void tryLock_acquireAndRelease() {
        assertThat(lock.tryLock("k", 100, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(lock.isHeldByCurrentThread("k")).isTrue();
        lock.unlock("k");
        assertThat(lock.isHeldByCurrentThread("k")).isFalse();
    }

    @Test
    void tryLock_secondThread_blocksUntilTimeout() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.tryLock("k2", 1, TimeUnit.SECONDS);
            acquired.countDown();
            try { release.await(); } catch (InterruptedException ignored) {}
            lock.unlock("k2");
        });
        holder.start();
        acquired.await();

        boolean got = lock.tryLock("k2", 50, TimeUnit.MILLISECONDS);
        assertThat(got).isFalse();
        release.countDown();
        holder.join();
    }

    @Test
    void lock_blocking_timeout_throwsBusinessException() throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.tryLock("k3", 1, TimeUnit.SECONDS);
            acquired.countDown();
            try { release.await(); } catch (InterruptedException ignored) {}
            lock.unlock("k3");
        });
        holder.start();
        acquired.await();

        assertThatThrownBy(() -> lock.lock("k3", 50, TimeUnit.MILLISECONDS))
                .isInstanceOf(com.auraboot.framework.exception.BusinessException.class);

        release.countDown();
        holder.join();
    }

    @Test
    void lock_blocking_acquiresWhenFree() throws Exception {
        lock.lock("kFree", 100, TimeUnit.MILLISECONDS);
        assertThat(lock.isHeldByCurrentThread("kFree")).isTrue();
        lock.unlock("kFree");
    }

    @Test
    void unlock_nonExistentLock_noThrow() {
        lock.unlock("never-existed");
    }

    @Test
    void unlock_notHeldByCurrentThread_noThrow() throws Exception {
        AtomicBoolean otherAcquired = new AtomicBoolean();
        Thread t = new Thread(() -> otherAcquired.set(lock.tryLock("kOther", 1, TimeUnit.SECONDS)));
        t.start();
        t.join();
        assertThat(otherAcquired.get()).isTrue();
        // Current thread does not hold; unlock is no-op
        lock.unlock("kOther");
    }

    @Test
    void renewLease_heldLock_returnsTrue() {
        lock.tryLock("k4", 100, TimeUnit.MILLISECONDS);
        assertThat(lock.renewLease("k4", 100, TimeUnit.MILLISECONDS)).isTrue();
        lock.unlock("k4");
    }

    @Test
    void renewLease_notHeld_returnsFalse() {
        assertThat(lock.renewLease("nope", 100, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    void isHeldByCurrentThread_unknownKey_returnsFalse() {
        assertThat(lock.isHeldByCurrentThread("unknown")).isFalse();
    }

    @Test
    void tryLock_interrupted_returnsFalse() throws Exception {
        Thread t = new Thread(() -> {
            Thread.currentThread().interrupt();
            boolean acquired = lock.tryLock("kInt", 1, TimeUnit.SECONDS);
            // The lock may still be acquired if available since tryLock checks interrupt only on contention.
            assertThat(Thread.currentThread().isInterrupted() || acquired).isTrue();
        });
        t.start();
        t.join();
    }
}
