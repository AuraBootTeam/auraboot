package com.auraboot.framework.connector.cdc;

import java.time.Instant;

/**
 * Snapshot of a running CDC engine — exposed via
 * {@link AbstractCdcConnectorAdapter#getStatus(String)} and persisted into
 * {@code ab_connector_cdc_engine}.
 *
 * @param engineId      stable engine identifier (matches {@code ab_connector_cdc_engine.pid})
 * @param state         lifecycle state, see {@link State}
 * @param lastPosition  source-specific position marker (bin-log file+offset, WAL LSN, …), as JSON
 * @param lastEventAt   timestamp of the most recently processed source event
 * @param heartbeatAt   timestamp of the most recent engine heartbeat
 * @param lagMs         observed lag between source commit and AuraBoot apply, in milliseconds
 * @since 5.3.0
 */
public record CdcStatus(
        String engineId,
        State state,
        String lastPosition,
        Instant lastEventAt,
        Instant heartbeatAt,
        Long lagMs
) {

    /** Engine lifecycle states (PRD 18 §B.3.3 column {@code ab_connector_cdc_engine.status}). */
    public enum State {
        IDLE,
        RUNNING,
        PAUSED,
        FAILED
    }
}
