package com.auraboot.framework.connector.saas.shopify;

import com.auraboot.framework.connector.saas.AbstractSaasConnectorAdapter;
import com.auraboot.framework.connector.saas.ReadCursor;
import com.auraboot.framework.connector.saas.SaasConnectorConfig;
import com.auraboot.framework.connector.sdk.ConnectorDescriptor;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Shopify Admin connector scaffold (PRD 18 §B.3.2).
 *
 * <p><strong>Status:</strong> SCAFFOLD — {@code discover()} and {@code read()} throw
 * {@link UnsupportedOperationException}. Real implementation lands in a follow-up PR.
 *
 * <h3>Planned implementation (follow-up PR)</h3>
 * <ul>
 *   <li><strong>Auth:</strong> per-shop OAuth — install flow against
 *       {@code https://{shop}.myshopify.com/admin/oauth/authorize}; permanent shop
 *       access token (no refresh) sent as {@code X-Shopify-Access-Token: <token>}
 *       header. Shop domain stored in {@link SaasConnectorConfig#extras()} as
 *       {@code shopDomain}.</li>
 *   <li><strong>Discovery:</strong> Admin REST has fixed resources; scaffold returns
 *       the 5 standard streams. Custom metafields require GraphQL Admin API
 *       {@code {nodes(ids:[...]) { metafields { ... } }}}.</li>
 *   <li><strong>Read:</strong> REST {@code GET /admin/api/2024-10/{resource}.json?limit=250}
 *       with {@code Link: &lt;next-url&gt;; rel="next"} header providing the cursor
 *       (extract {@code page_info=} from URL). Incremental via {@code updated_at_min}.
 *       <strong>GraphQL Admin API preferred</strong> for {@code orders} and {@code products}
 *       (bulk operations for large shops; REST capped at 250 items/page).</li>
 *   <li><strong>Rate limit:</strong> REST leaky bucket 40 calls/app/store (2/s refill);
 *       GraphQL cost-based 1000 points/app/store; respect {@code X-Shopify-Shop-Api-Call-Limit}.</li>
 * </ul>
 *
 * @since 5.3.0
 */
@Component
public class ShopifyConnectorAdapter extends AbstractSaasConnectorAdapter {

    private static final List<String> STREAMS = List.of(
            "orders", "customers", "products", "inventory_items", "fulfillments");

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(
            "saas-shopify",
            "Shopify Admin via REST + GraphQL (per-shop OAuth, Link-header cursor)",
            STREAMS);

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Map<String, Object> discover(SaasConnectorConfig config) {
        // TODO(follow-up PR): Static resource list + GraphQL introspection for custom
        // metafields per resource (orders, products, customers commonly carry custom fields).
        throw new UnsupportedOperationException(
                "NOT_YET_IMPLEMENTED: Shopify discover() — wire static REST table + GraphQL metafield probe");
    }

    @Override
    public Stream<Map<String, Object>> read(SaasConnectorConfig config, String streamName, ReadCursor cursor) {
        // TODO(follow-up PR): GET /admin/api/2024-10/<streamName>.json?limit=250&
        //   updated_at_min=<cursor.since>&page_info=<cursor.pageToken>; parse Link header
        //   for next page_info; prefer GraphQL bulkOperationRunQuery for orders/products at scale.
        throw new UnsupportedOperationException(
                "NOT_YET_IMPLEMENTED: Shopify read(" + streamName
                        + ") — wire REST cursor + GraphQL bulk for large objects");
    }
}
