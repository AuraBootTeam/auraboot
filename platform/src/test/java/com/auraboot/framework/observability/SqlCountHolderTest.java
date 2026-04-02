package com.auraboot.framework.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SqlCountHolderTest {

    @BeforeEach
    void setUp() {
        SqlCountHolder.reset();
    }

    @AfterEach
    void cleanup() {
        SqlCountHolder.reset();
    }

    @Test
    void startsAtZero() {
        assertThat(SqlCountHolder.get()).isEqualTo(0);
    }

    @Test
    void incrementsCorrectly() {
        SqlCountHolder.increment();
        SqlCountHolder.increment();
        SqlCountHolder.increment();
        assertThat(SqlCountHolder.get()).isEqualTo(3);
    }

    @Test
    void resetClearsCount() {
        SqlCountHolder.increment();
        SqlCountHolder.increment();
        SqlCountHolder.reset();
        assertThat(SqlCountHolder.get()).isEqualTo(0);
    }

    @Test
    void threadIsolation() throws Exception {
        SqlCountHolder.increment();

        int[] otherThreadCount = new int[1];
        Thread t = new Thread(() -> {
            otherThreadCount[0] = SqlCountHolder.get();
            SqlCountHolder.reset();
        });
        t.start();
        t.join();

        assertThat(otherThreadCount[0]).isEqualTo(0);
        assertThat(SqlCountHolder.get()).isEqualTo(1);
    }
}
