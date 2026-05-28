package com.auraboot.framework.connector.cdc;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default {@link CdcEngine} implementation. <strong>This is a state-machine stub
 * only</strong> — it does not actually subscribe to a source binlog/WAL stream.
 *
 * <p>The MVP shipped in 18-B Week-1 wires up the SPI surface, the registry, persistence,
 * and tests; real Debezium engine integration lands in v0.2.
 *
 * <p>TODO v0.2: integrate {@code io.debezium:debezium-embedded} (or equivalent
 * standalone engine factory) here, wire {@code start()} to {@code DebeziumEngine.create(...)},
 * forward change events to a {@code MqProducer} topic, and update {@link #lastPosition}
 * on every committed offset.
 *
 * @since 5.3.0
 */
public class DebeziumCdcEngine implements CdcEngine {

    private final String engineId;
    private final CdcConfig config;
    private final AtomicReference<CdcStatus.State> state =
            new AtomicReference<>(CdcStatus.State.IDLE);
    private volatile String lastPosition;
    private volatile Instant lastEventAt;
    private volatile Instant heartbeatAt;
    private volatile Long lagMs;

    public DebeziumCdcEngine(String engineId, CdcConfig config) {
        if (engineId == null || engineId.isBlank()) {
            throw new IllegalArgumentException("engineId must not be blank");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.engineId = engineId;
        this.config = config;
    }

    @Override
    public String getEngineId() {
        return engineId;
    }

    @Override
    public synchronized void start() {
        CdcStatus.State current = state.get();
        if (current == CdcStatus.State.RUNNING) {
            throw new IllegalStateException("Engine " + engineId + " already running");
        }
        if (current == CdcStatus.State.FAILED) {
            // explicit re-start clears failure marker
            state.set(CdcStatus.State.IDLE);
        }
        // TODO v0.2: io.debezium.engine.DebeziumEngine.create(...).build().run();
        state.set(CdcStatus.State.RUNNING);
        heartbeatAt = Instant.now();
        lagMs = 0L;
    }

    @Override
    public synchronized void stop() {
        // TODO v0.2: debeziumEngine.close();
        state.set(CdcStatus.State.IDLE);
    }

    @Override
    public synchronized void pause() {
        if (state.get() != CdcStatus.State.RUNNING) {
            throw new IllegalStateException("Engine " + engineId + " is not running, cannot pause");
        }
        state.set(CdcStatus.State.PAUSED);
    }

    @Override
    public synchronized void resume() {
        if (state.get() != CdcStatus.State.PAUSED) {
            throw new IllegalStateException("Engine " + engineId + " is not paused, cannot resume");
        }
        state.set(CdcStatus.State.RUNNING);
        heartbeatAt = Instant.now();
    }

    @Override
    public CdcStatus getStatus() {
        return new CdcStatus(engineId, state.get(), lastPosition, lastEventAt, heartbeatAt, lagMs);
    }

    /** Visible for testing / future Debezium event-loop integration. */
    public void recordEvent(String position, long observedLagMs) {
        this.lastPosition = position;
        this.lastEventAt = Instant.now();
        this.heartbeatAt = this.lastEventAt;
        this.lagMs = observedLagMs;
    }

    public CdcConfig config() {
        return config;
    }
}
