package com.auraboot.framework.observability;

/**
 * Thread-local counter for SQL statements executed during a single HTTP request.
 *
 * <p>Lifecycle: {@link SqlCountFilter} resets the counter at request start and
 * reads it after the request completes. {@link SqlCountInterceptor} increments
 * the counter on every MyBatis query/update execution.
 */
public final class SqlCountHolder {

    public static final int WARN_THRESHOLD_DEFAULT = 10;
    public static final int ERROR_THRESHOLD_DEFAULT = 50;

    private static final ThreadLocal<int[]> COUNTER = ThreadLocal.withInitial(() -> new int[]{0});

    private SqlCountHolder() {}

    public static int get() {
        return COUNTER.get()[0];
    }

    public static void increment() {
        COUNTER.get()[0]++;
    }

    public static void reset() {
        COUNTER.remove();
    }
}
