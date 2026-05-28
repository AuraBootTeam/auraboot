package com.auraboot.framework.connector.saas.salesforce;

import com.auraboot.framework.connector.saas.AbstractSaasConnectorAdapter;
import com.auraboot.framework.connector.saas.ReadCursor;
import com.auraboot.framework.connector.saas.SaasConnectorConfig;
import com.auraboot.framework.connector.sdk.ConnectorDescriptor;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Salesforce CRM connector scaffold (PRD 18 §B.3.2).
 *
 * <p><strong>Status:</strong> SCAFFOLD — {@code discover()} and {@code read()} throw
 * {@link UnsupportedOperationException}. Real implementation lands in a follow-up PR.
 *
 * <h3>Planned implementation (follow-up PR)</h3>
 * <ul>
 *   <li><strong>Auth:</strong> OAuth2 web-server flow against {@code login.salesforce.com}
 *       (sandbox: {@code test.salesforce.com}). Refresh-token grant for unattended sync;
 *       per-org {@code instance_url} (e.g. {@code https://acme.my.salesforce.com}) returned
 *       in the token response — used as {@link SaasConnectorConfig#apiBaseUrl()}.</li>
 *   <li><strong>Discovery:</strong> {@code GET /services/data/v60.0/sobjects} returns all
 *       sobject metadata; combined with {@code /describe} per object for fields. Scaffold
 *       below returns a hardcoded list of the 6 core CRM objects.</li>
 *   <li><strong>Read:</strong> Bulk API 2.0 query jobs ({@code POST /services/data/v60.0/jobs/query}
 *       with SOQL body) for full snapshots; REST {@code /query?q=...} for small streams.
 *       Incremental uses {@code SystemModstamp >= :since} as the SOQL filter, with
 *       {@code SystemModstamp} as the cursor field.</li>
 *   <li><strong>Rate limit:</strong> Salesforce daily API quota (~15k/24h for standard orgs);
 *       Bulk API 2.0 has separate caps. Respect {@code Sforce-Limit-Info} response header.</li>
 *   <li><strong>Schema drift:</strong> custom fields ({@code __c} suffix) need re-discover.</li>
 * </ul>
 *
 * @since 5.3.0
 */
@Component
public class SalesforceConnectorAdapter extends AbstractSaasConnectorAdapter {

    private static final List<String> STREAMS = List.of(
            "Account", "Contact", "Opportunity", "Lead", "Case", "User");

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(
            "saas-salesforce",
            "Salesforce CRM via Bulk API 2.0 + SOQL (OAuth2 refresh-token flow)",
            STREAMS);

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Map<String, Object> discover(SaasConnectorConfig config) {
        // TODO(follow-up PR): GET /services/data/v60.0/sobjects + /describe per object
        throw new UnsupportedOperationException(
                "NOT_YET_IMPLEMENTED: Salesforce discover() — wire Bulk API 2.0 + sobject describe");
    }

    @Override
    public Stream<Map<String, Object>> read(SaasConnectorConfig config, String streamName, ReadCursor cursor) {
        // TODO(follow-up PR): Bulk API 2.0 query job with SOQL "SELECT ... FROM <streamName>
        //   WHERE SystemModstamp >= :cursor.since ORDER BY SystemModstamp ASC LIMIT n"
        throw new UnsupportedOperationException(
                "NOT_YET_IMPLEMENTED: Salesforce read(" + streamName + ") — wire SOQL + SystemModstamp cursor");
    }
}
