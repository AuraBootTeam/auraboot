package com.auraboot.framework.connector.saas;

import com.auraboot.framework.connector.sdk.AbstractConnectorAdapter;
import com.auraboot.framework.connector.sdk.ConnectorInvocationContext;
import com.auraboot.framework.connector.sdk.ConnectorInvocationResult;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Skeletal {@link com.auraboot.framework.connector.sdk.ConnectorAdapter ConnectorAdapter}
 * extension for SaaS sources (PRD 18 §B.3.2).
 *
 * <p>Subclasses provide two SaaS-specific lifecycle hooks beyond the SDK contract:
 * <ul>
 *   <li>{@link #discover(SaasConnectorConfig)} — list available streams/objects</li>
 *   <li>{@link #read(SaasConnectorConfig, String, ReadCursor)} — page through one stream</li>
 * </ul>
 *
 * <p>The classic {@code invoke()} entry from the SDK forwards to a single-record
 * "ping" call; the real high-throughput sync path goes through {@code read()} which
 * supports paginated, cursor-based incremental sync.
 *
 * @since 5.3.0
 */
public abstract class AbstractSaasConnectorAdapter extends AbstractConnectorAdapter {

    /**
     * List the streams/objects this connector can sync (e.g. Salesforce: Account /
     * Contact / Opportunity). The list is typically static for SaaS vendors with a
     * fixed object model; some (Salesforce, HubSpot custom objects) may query the
     * vendor for runtime discovery.
     *
     * @param config non-null SaaS configuration
     * @return map of {@code streamName -> metadata} where metadata typically includes
     *         {@code fields}, {@code primaryKey}, {@code cursorField}, {@code supportsIncremental}
     */
    public abstract Map<String, Object> discover(SaasConnectorConfig config);

    /**
     * Read records from one stream, optionally resumed from {@code cursor}.
     *
     * <p>Returned stream is lazy: pages are fetched on demand. The adapter
     * implementation is responsible for honouring {@link SaasConnectorConfig#rateLimitPerMinute()}.
     *
     * @param config     non-null SaaS configuration
     * @param streamName the stream to read (must appear in {@link #discover}'s keys)
     * @param cursor     optional resume point; pass {@link ReadCursor#empty()} for full snapshot
     * @return lazy stream of record maps; never null
     */
    public abstract Stream<Map<String, Object>> read(
            SaasConnectorConfig config, String streamName, ReadCursor cursor);

    /**
     * List the static stream names supported by this connector. Default returns
     * {@link com.auraboot.framework.connector.sdk.ConnectorDescriptor#supportedEndpointCodes()}
     * from the descriptor — subclasses typically override the descriptor list rather
     * than this method.
     */
    public List<String> supportedStreams() {
        return descriptor().supportedEndpointCodes();
    }

    /**
     * Default SDK invocation path — SaaS adapters route real bulk sync through
     * {@link #read} so the SDK call here is a stub until a follow-up PR wires
     * single-record fetch semantics.
     */
    @Override
    public ConnectorInvocationResult invoke(ConnectorInvocationContext context) {
        return ConnectorInvocationResult.failure(
                "NOT_YET_IMPLEMENTED: " + descriptor().protocolType()
                        + " SaaS adapter scaffold — use read()/discover() in follow-up PR");
    }

    /**
     * Default scaffold test: returns {@code false} until real auth handshake is wired.
     * Subclasses override to perform a /me or /ping call.
     */
    @Override
    public boolean testConnection(Long tenantId, String connectorPid) {
        return false;
    }
}
