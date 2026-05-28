package com.auraboot.framework.connector.cdc;

/**
 * Lifecycle handle for an active change-data-capture stream.
 *
 * <p>One {@code CdcEngine} instance corresponds to one row in
 * {@code ab_connector_cdc_engine}. Implementations are expected to be thread-safe
 * — {@link #stop()} / {@link #pause()} may be invoked from a different thread than
 * the one that called {@link #start()}.
 *
 * <p>The default implementation {@link DebeziumCdcEngine} ships as an in-memory stub
 * (state-machine only); a real embedded Debezium adapter will arrive in v0.2.
 *
 * @since 5.3.0
 */
public interface CdcEngine {

    /**
     * Stable engine identifier (== {@code ab_connector_cdc_engine.pid}).
     */
    String getEngineId();

    /**
     * Transition from {@link CdcStatus.State#IDLE} to {@link CdcStatus.State#RUNNING}.
     *
     * @throws IllegalStateException if the engine is already running or has failed
     */
    void start();

    /**
     * Permanently stop the engine. Subsequent {@link #start()} calls re-initialise it.
     */
    void stop();

    /**
     * Suspend without losing position. Use {@link #resume()} to continue.
     *
     * @throws IllegalStateException if not currently {@link CdcStatus.State#RUNNING}
     */
    void pause();

    /**
     * Resume from {@link CdcStatus.State#PAUSED}.
     *
     * @throws IllegalStateException if not currently paused
     */
    void resume();

    /**
     * Returns a non-null, immutable snapshot of the engine state.
     */
    CdcStatus getStatus();
}
