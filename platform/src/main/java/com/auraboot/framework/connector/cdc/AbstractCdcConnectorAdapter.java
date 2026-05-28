package com.auraboot.framework.connector.cdc;

import com.auraboot.framework.connector.sdk.AbstractConnectorAdapter;

/**
 * Skeletal {@link com.auraboot.framework.connector.sdk.ConnectorAdapter ConnectorAdapter}
 * extension for CDC-capable sources (PRD 18 §B.3.1).
 *
 * <p>Concrete subclasses implement the three CDC lifecycle hooks; the rest of the
 * adapter contract ({@code descriptor()}, {@code invoke}, {@code testConnection}) is
 * inherited from the connector SDK.
 *
 * @since 5.3.0
 */
public abstract class AbstractCdcConnectorAdapter extends AbstractConnectorAdapter {

    /**
     * Start a CDC stream for the given connector. The returned engine is registered
     * (typically by {@link CdcConnectorRegistry}) and reachable through
     * {@link #getStatus(String)}.
     *
     * @param config non-null CDC configuration (validated by {@link CdcConfig} ctor)
     * @return a live {@link CdcEngine} handle
     */
    public abstract CdcEngine startCdc(CdcConfig config);

    /**
     * Stop a previously-started engine. Idempotent: stopping an unknown engineId is a no-op.
     */
    public abstract void stopCdc(String engineId);

    /**
     * Look up status of a running engine.
     *
     * @return engine status, or {@code null} when no engine with that id is registered
     */
    public abstract CdcStatus getStatus(String engineId);
}
