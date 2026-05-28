package com.auraboot.framework.connector.saas.hubspot;

import com.auraboot.framework.connector.saas.AbstractSaasConnectorAdapter;
import com.auraboot.framework.connector.saas.ReadCursor;
import com.auraboot.framework.connector.saas.SaasConnectorConfig;
import com.auraboot.framework.connector.sdk.ConnectorDescriptor;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * HubSpot CRM connector scaffold (PRD 18 §B.3.2).
 *
 * <p><strong>Status:</strong> SCAFFOLD — {@code discover()} and {@code read()} throw
 * {@link UnsupportedOperationException}. Real implementation lands in a follow-up PR.
 *
 * <h3>Planned implementation (follow-up PR)</h3>
 * <ul>
 *   <li><strong>Auth:</strong> OAuth2 authorization-code flow at
 *       {@code https://app.hubspot.com/oauth/authorize}; token endpoint
 *       {@code https://api.hubapi.com/oauth/v1/token}. Refresh-token grant for sync.
 *       Scopes per object (e.g. {@code crm.objects.contacts.read}).</li>
 *   <li><strong>Discovery:</strong> v3 schemas API {@code GET /crm/v3/schemas} returns all
 *       object types incl. custom objects. Scaffold below returns the 6 core objects.</li>
 *   <li><strong>Read:</strong> v3 search endpoint
 *       {@code POST /crm/v3/objects/{objectType}/search} with body
 *       {@code {filterGroups, sorts, after, limit}} — supports incremental via
 *       {@code hs_lastmodifieddate >= :since}. Pagination via {@code paging.next.after}
 *       cursor returned in response, fed back as {@code ?after=}.</li>
 *   <li><strong>Rate limit:</strong> 100 req/10s burst, 250k/day on Pro tier;
 *       respect {@code X-HubSpot-RateLimit-Remaining} header.</li>
 *   <li><strong>Associations:</strong> separate {@code /crm/v3/associations/...} batch
 *       calls to hydrate contact↔company, deal↔contact links.</li>
 * </ul>
 *
 * @since 5.3.0
 */
@Component
public class HubspotConnectorAdapter extends AbstractSaasConnectorAdapter {

    private static final List<String> STREAMS = List.of(
            "companies", "contacts", "deals", "tickets", "line_items", "products");

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(
            "saas-hubspot",
            "HubSpot CRM via v3 REST API (OAuth2 + search endpoint with ?after cursor)",
            STREAMS);

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Map<String, Object> discover(SaasConnectorConfig config) {
        // TODO(follow-up PR): GET /crm/v3/schemas to enumerate standard + custom objects
        throw new UnsupportedOperationException(
                "NOT_YET_IMPLEMENTED: HubSpot discover() — wire /crm/v3/schemas listing");
    }

    @Override
    public Stream<Map<String, Object>> read(SaasConnectorConfig config, String streamName, ReadCursor cursor) {
        // TODO(follow-up PR): POST /crm/v3/objects/{streamName}/search with filter
        //   hs_lastmodifieddate >= cursor.since, sort asc, paginate via paging.next.after
        throw new UnsupportedOperationException(
                "NOT_YET_IMPLEMENTED: HubSpot read(" + streamName + ") — wire v3 search + ?after pagination");
    }
}
