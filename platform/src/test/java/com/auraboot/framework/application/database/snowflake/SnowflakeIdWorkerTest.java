package com.auraboot.framework.application.database.snowflake;

import com.auraboot.framework.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic unit tests for {@link SnowflakeIdWorker} — bit layout, uniqueness under
 * concurrency, constructor/setter guard rails, and the clock-rollback fail-fast.
 */
@DisplayName("SnowflakeIdWorker — bit layout, guards, uniqueness")
class SnowflakeIdWorkerTest {

    private static final long TWEPOCH = 1704067200000L;
    private static final long TIMESTAMP_LEFT_SHIFT = 22L;
    private static final long DATACENTER_ID_SHIFT = 17L;
    private static final long WORKER_ID_SHIFT = 12L;

    @Test
    @DisplayName("constructor and setters reject out-of-range worker/datacenter ids")
    void guardRails() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdWorker(32, 0));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdWorker(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdWorker(0, 32));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdWorker(0, -1));

        SnowflakeIdWorker worker = new SnowflakeIdWorker(0, 0);
        assertThrows(IllegalArgumentException.class, () -> worker.setWorkerId(99));
        assertThrows(IllegalArgumentException.class, () -> worker.setDatacenterId(-2));
        worker.setWorkerId(7);
        worker.setDatacenterId(3);
    }

    @Test
    @DisplayName("ids encode timestamp, datacenter, worker, and sequence in the documented bits")
    void bitLayout() {
        SnowflakeIdWorker worker = new SnowflakeIdWorker(21, 5);
        long before = System.currentTimeMillis();
        long id = worker.nextId();
        long after = System.currentTimeMillis();

        long sequence = id & 0xFFF;
        long workerId = (id >> WORKER_ID_SHIFT) & 0x1F;
        long datacenterId = (id >> DATACENTER_ID_SHIFT) & 0x1F;
        long timestamp = (id >> TIMESTAMP_LEFT_SHIFT) + TWEPOCH;

        assertEquals(0, sequence);
        assertEquals(21, workerId);
        assertEquals(5, datacenterId);
        assertTrue(timestamp >= before && timestamp <= after, "timestamp must be within the call window");
    }

    @Test
    @DisplayName("sequential ids in the same millisecond increment the sequence without duplicates")
    void sequenceIncrements() {
        SnowflakeIdWorker worker = new SnowflakeIdWorker(1, 1);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            assertTrue(ids.add(worker.nextId()), "ids must be unique");
        }
    }

    @Test
    @DisplayName("concurrent generators produce globally unique ids")
    void concurrentUniqueness() throws InterruptedException {
        SnowflakeIdWorker worker = new SnowflakeIdWorker(2, 2);
        int threads = 8;
        int perThread = 500;
        Set<Long> ids = java.util.Collections.synchronizedSet(new HashSet<>());
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                ready.countDown();
                for (int i = 0; i < perThread; i++) {
                    ids.add(worker.nextId());
                }
                done.countDown();
            }).start();
        }
        done.await();
        assertEquals(threads * perThread, ids.size());
    }

    @Test
    @DisplayName("clock rollback fails fast with a BusinessException")
    void clockRollbackRejected() {
        // lastTimestamp is seeded from the first generation; a subclass pins timeGen()
        // into the past so the next call observes a rolled-back clock.
        SnowflakeIdWorker drifting = new SnowflakeIdWorker(1, 1) {
            private boolean first = true;

            @Override
            protected long timeGen() {
                if (first) {
                    first = false;
                    return System.currentTimeMillis();
                }
                return System.currentTimeMillis() - 10_000;
            }
        };
        drifting.nextId();
        BusinessException rolled = assertThrows(BusinessException.class, drifting::nextId);
        assertTrue(rolled.getMessage().contains("Clock moved backwards"));
    }
}
