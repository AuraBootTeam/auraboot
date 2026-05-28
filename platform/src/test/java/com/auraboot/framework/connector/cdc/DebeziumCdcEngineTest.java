package com.auraboot.framework.connector.cdc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the {@link DebeziumCdcEngine} state machine stub.
 *
 * <p>Real Debezium engine integration is deferred to v0.2; these tests pin the
 * lifecycle contract that the future real engine must continue to honour.
 */
class DebeziumCdcEngineTest {

    private CdcConfig config;
    private DebeziumCdcEngine engine;

    @BeforeEach
    void setUp() {
        config = new CdcConfig(
                "conn-1",
                "mysql",
                "localhost",
                3306,
                "appdb",
                List.of("orders", "customers"),
                Map.of("snapshot.mode", "initial"),
                10_000L);
        engine = new DebeziumCdcEngine("engine-1", config);
    }

    @Test
    void start_transitions_idle_to_running_and_sets_heartbeat() {
        assertThat(engine.getStatus().state()).isEqualTo(CdcStatus.State.IDLE);
        engine.start();
        CdcStatus s = engine.getStatus();
        assertThat(s.state()).isEqualTo(CdcStatus.State.RUNNING);
        assertThat(s.heartbeatAt()).isNotNull();
        assertThat(s.lagMs()).isEqualTo(0L);
        assertThat(s.engineId()).isEqualTo("engine-1");
    }

    @Test
    void double_start_rejected() {
        engine.start();
        assertThatThrownBy(() -> engine.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already running");
    }

    @Test
    void pause_resume_round_trip() {
        engine.start();
        engine.pause();
        assertThat(engine.getStatus().state()).isEqualTo(CdcStatus.State.PAUSED);
        engine.resume();
        assertThat(engine.getStatus().state()).isEqualTo(CdcStatus.State.RUNNING);

        // pause from non-running -> reject
        engine.stop();
        assertThatThrownBy(() -> engine.pause())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> engine.resume())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stop_returns_engine_to_idle_and_supports_restart() {
        engine.start();
        engine.stop();
        assertThat(engine.getStatus().state()).isEqualTo(CdcStatus.State.IDLE);
        // restart OK
        engine.start();
        assertThat(engine.getStatus().state()).isEqualTo(CdcStatus.State.RUNNING);
    }

    @Test
    void recordEvent_updates_position_lag_and_timestamps() {
        engine.start();
        engine.recordEvent("{\"file\":\"mysql-bin.000001\",\"pos\":4321}", 250L);
        CdcStatus s = engine.getStatus();
        assertThat(s.lastPosition()).contains("mysql-bin.000001");
        assertThat(s.lagMs()).isEqualTo(250L);
        assertThat(s.lastEventAt()).isNotNull();
        assertThat(s.heartbeatAt()).isNotNull();
    }

    @Test
    void constructor_validates_inputs() {
        assertThatThrownBy(() -> new DebeziumCdcEngine(null, config))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DebeziumCdcEngine("", config))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DebeziumCdcEngine("engine-x", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
