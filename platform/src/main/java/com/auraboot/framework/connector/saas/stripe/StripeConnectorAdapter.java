package com.auraboot.framework.connector.saas.stripe;

import com.auraboot.framework.connector.saas.AbstractSaasConnectorAdapter;
import com.auraboot.framework.connector.saas.ReadCursor;
import com.auraboot.framework.connector.saas.SaasConnectorConfig;
import com.auraboot.framework.connector.sdk.ConnectorDescriptor;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Stripe Payments connector scaffold (PRD 18 §B.3.2).
 *
 * <p><strong>Status:</strong> SCAFFOLD — {@code discover()} and {@code read()} throw
 * {@link UnsupportedOperationException}. Real implementation lands in a follow-up PR.
 *
 * <h3>Planned implementation (follow-up PR)</h3>
 * <ul>
 *   <li><strong>Auth:</strong> Stripe API key auth (no OAuth) — restricted-key
 *       {@code rk_live_*} or secret-key {@code sk_live_*} via
 *       {@code Authorization: Bearer <key>} header. Stored under
 *       {@link SaasConnectorConfig#clientSecret()}; {@code authType="apikey"}.</li>
 *   <li><strong>Discovery:</strong> Stripe object model is fixed — scaffold returns
 *       the 5 standard streams; no runtime discovery call needed.</li>
 *   <li><strong>Read:</strong> {@code GET /v1/{resource}?limit=100&starting_after=&lt;cursor&gt;}
 *       paginates via {@code starting_after} (Stripe IDs are time-ordered). Incremental
 *       via {@code created[gte]=&lt;unix_ts&gt;} or via {@code /v1/events} polling for
 *       change feed (recommended for orders/charges).</li>
 *   <li><strong>Idempotency:</strong> all mutating calls require {@code Idempotency-Key}
 *       header (not relevant for read-only sync but required if we add write-back).</li>
 *   <li><strong>Rate limit:</strong> 100 read/s + 100 write/s soft; respect
 *       {@code 429} with exponential backoff.</li>
 * </ul>
 *
 * @since 5.3.0
 */
@Component
public class StripeConnectorAdapter extends AbstractSaasConnectorAdapter {

    private static final List<String> STREAMS = List.of(
            "customers", "charges", "invoices", "subscriptions", "events");

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(
            "saas-stripe",
            "Stripe Payments via REST API (API-key auth, ?starting_after cursor pagination)",
            STREAMS);

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Map<String, Object> discover(SaasConnectorConfig config) {
        // TODO(follow-up PR): Stripe object set is static — return cached metadata
        // mapping each stream to its primary key + cursor field (created/updated).
        throw new UnsupportedOperationException(
                "NOT_YET_IMPLEMENTED: Stripe discover() — wire static metadata table");
    }

    @Override
    public Stream<Map<String, Object>> read(SaasConnectorConfig config, String streamName, ReadCursor cursor) {
        // TODO(follow-up PR): GET /v1/<streamName>?limit=100&starting_after=<cursor.pageToken>
        //   for forward pagination; use created[gte]=<since> for incremental window.
        throw new UnsupportedOperationException(
                "NOT_YET_IMPLEMENTED: Stripe read(" + streamName + ") — wire ?starting_after + Idempotency-Key");
    }
}
