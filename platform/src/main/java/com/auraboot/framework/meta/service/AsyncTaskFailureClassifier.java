package com.auraboot.framework.meta.service;

import org.springframework.dao.TransientDataAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.sql.SQLTransientException;

/**
 * Narrow type-based classifier for retrying an entire asynchronous task.
 *
 * <p>Business and validation failures are terminal by default. Message matching is deliberately
 * excluded because provider text and translated validation messages are not stable contracts.</p>
 */
public final class AsyncTaskFailureClassifier {

    private AsyncTaskFailureClassifier() {
    }

    public static boolean isRetryable(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof ConnectException
                    || current instanceof SQLTransientException
                    || current instanceof TransientDataAccessException) {
                return true;
            }
            if (current instanceof InterruptedException) {
                return false;
            }
            current = current.getCause();
        }
        return false;
    }
}
