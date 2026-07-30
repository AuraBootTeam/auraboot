package com.auraboot.framework.meta.service;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncTaskFailureClassifierTest {

    @Test
    void retriesOnlyTypedTransientFailuresAcrossCauseChain() {
        assertThat(AsyncTaskFailureClassifier.isRetryable(
                new IllegalStateException("business rule rejected"))).isFalse();
        assertThat(AsyncTaskFailureClassifier.isRetryable(
                new RuntimeException(new ConnectException("refused")))).isTrue();
        assertThat(AsyncTaskFailureClassifier.isRetryable(
                new RuntimeException(new SQLTransientConnectionException("pool exhausted")))).isTrue();
    }

    @Test
    void interruptedExecutionIsTerminal() {
        assertThat(AsyncTaskFailureClassifier.isRetryable(
                new RuntimeException(new InterruptedException("cancelled")))).isFalse();
    }
}
