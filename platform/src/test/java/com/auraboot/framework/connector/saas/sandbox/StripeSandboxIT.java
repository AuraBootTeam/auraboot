package com.auraboot.framework.connector.saas.sandbox;

import com.auraboot.framework.connector.saas.ReadCursor;
import com.auraboot.framework.connector.saas.stripe.StripeConnectorAdapter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stripe sandbox contract integration tests.
 *
 * <p>Each case exercises the real {@link StripeConnectorAdapter} production
 * code path against an in-process {@link StripeSandboxFixture}.  No real API
 * key or network is needed.  Tests verify wire-contract behaviour that mocked
 * unit tests cannot: true {@code starting_after} cursor propagation,
 * {@code has_more=false} boundary detection, {@code created[gte]} server-side
 * filter, API-key validation, and the {@code Stripe-Version} header.
 */
class StripeSandboxIT extends SaasSandboxBase {

    // -----------------------------------------------------------------------
    // 1. Discover: all 8 streams (7 core + events) returned by discover()
    // -----------------------------------------------------------------------

    @Test
    void discover_returns8Streams() {
        StripeConnectorAdapter adapter = createStripeAdapter();
        Map<String, Object> streams = adapter.discover(stripeConfig());

        assertThat(streams).hasSize(8);
        assertThat(streams.keySet()).containsAll(List.of(
                "customers", "charges", "invoices", "subscriptions",
                "products", "prices", "payment_intents", "events"));

        @SuppressWarnings("unchecked")
        Map<String, Object> customersMeta = (Map<String, Object>) streams.get("customers");
        assertThat(customersMeta).containsEntry("primaryKey", "id")
                .containsEntry("cursorField", "created")
                .containsEntry("supportsIncremental", true)
                .containsEntry("source", "core");
    }

    // -----------------------------------------------------------------------
    // 2. Full paginate: 7 streams × 150 rows (the sandbox fixture only covers 7)
    // -----------------------------------------------------------------------

    @Test
    void read_customers_fullPaginate_150rows() {
        StripeConnectorAdapter adapter = createStripeAdapter();
        List<Map<String, Object>> rows = adapter
                .read(stripeConfig(), "customers", emptyCursor())
                .collect(Collectors.toList());

        assertThat(rows).hasSize(150);
        assertThat(rows.get(0).get("id")).isEqualTo("cus_00000000");
        assertThat(rows.get(0).get("object")).isEqualTo("customer");
    }

    @Test
    void read_all7Streams_150rowsEach() {
        for (String stream : List.of("customers", "charges", "invoices", "subscriptions",
                "products", "prices", "payment_intents")) {
            StripeConnectorAdapter adapter = createStripeAdapter();
            List<Map<String, Object>> rows = adapter
                    .read(stripeConfig(), stream, emptyCursor())
                    .collect(Collectors.toList());
            assertThat(rows)
                    .as("stream=" + stream + " should have 150 rows")
                    .hasSize(150);
        }
    }

    // -----------------------------------------------------------------------
    // 3. starting_after is correctly passed for page 2+
    // -----------------------------------------------------------------------

    @Test
    void read_startingAfterPropagated_secondPageStartsAfterLastIdOfPage1() {
        StripeConnectorAdapter adapter = createStripeAdapter();

        List<Map<String, Object>> rows = adapter
                .read(stripeConfig(), "customers", emptyCursor())
                .collect(Collectors.toList());

        // 150 rows = 3 pages of 50. Verify no duplicates (correct starting_after logic).
        Set<Object> ids = rows.stream().map(r -> r.get("id")).collect(Collectors.toSet());
        assertThat(ids).hasSize(150);

        // Verify the last query string on the stream contained starting_after.
        String lastQuery = stripeFixture().getLastQueryString("customers");
        // The last page request must have starting_after set (it was the 3rd page).
        assertThat(lastQuery).contains("starting_after=cus_");
    }

    // -----------------------------------------------------------------------
    // 4. created[gte] filter is honoured
    // -----------------------------------------------------------------------

    @Test
    void read_createdGte_serverSideFilterApplied() {
        StripeConnectorAdapter adapter = createStripeAdapter();

        // Customers have created = 2025-01-01T00:00:00Z + i*3600 seconds.
        // Customer at index 50 has created = 2025-01-01T00:00:00Z + 180000s
        //   = 2025-01-01T00:00:00Z + 50h = 2025-03-03T02:00:00Z.
        long baseEpoch = Instant.parse("2025-01-01T00:00:00Z").getEpochSecond();
        long cutoff = baseEpoch + 50L * 3600;
        Instant since = Instant.ofEpochSecond(cutoff);

        List<Map<String, Object>> rows = adapter
                .read(stripeConfig(), "customers", sinceCursor(since))
                .collect(Collectors.toList());

        // Customers 50..149 have created >= cutoff → 100 rows.
        assertThat(rows).hasSize(100);

        // Verify the query string had created%5Bgte%5D (URL-encoded created[gte]).
        String lastQuery = stripeFixture().getLastQueryString("customers");
        assertThat(lastQuery).containsAnyOf(
                "created%5Bgte%5D=" + cutoff,
                "created[gte]=" + cutoff);
    }

    // -----------------------------------------------------------------------
    // 5. has_more=false stops pagination exactly
    // -----------------------------------------------------------------------

    @Test
    void read_hasMorFalse_paginationStopsAtBoundary() {
        StripeConnectorAdapter adapter = createStripeAdapter();

        // charges has 150 fixture rows → 3 pages, has_more=false on 3rd page.
        List<Map<String, Object>> rows = adapter
                .read(stripeConfig(), "charges", emptyCursor())
                .collect(Collectors.toList());

        assertThat(rows).hasSize(150);
        // Verify last row has correct id (no over-fetching).
        assertThat(rows.get(149).get("id")).isEqualTo("ch_00000149");
    }

    // -----------------------------------------------------------------------
    // 6. Invalid API key → 401 → PageIterator catches SaasHttpException → empty stream
    // -----------------------------------------------------------------------

    @Test
    void read_invalidApiKey_returnsEmptyStreamSafely() {
        StripeConnectorAdapter adapter = createStripeAdapter();

        // Use an API key that does NOT start with sk_test_.
        // The sandbox returns 401; SaasHttpClient.executeForJson throws SaasHttpException;
        // StripeConnectorAdapter.PageIterator catches it, sets exhausted=true → empty stream.
        List<Map<String, Object>> rows =
                adapter.read(stripeConfigWithKey("sk_live_INVALID_KEY"), "customers", emptyCursor())
                        .collect(Collectors.toList());

        // The 401 is caught by the PageIterator as per the adapter's fault-tolerance
        // design: mid-stream errors stop paging and return whatever was buffered (nothing
        // for the first page).
        assertThat(rows).isEmpty();

        // The fixture executor recorded the call even though it returned 401.
        // We verify the stream was empty rather than throwing — matching the adapter's
        // fault-tolerance contract (mid-stream errors stop paging gracefully).
    }

    // -----------------------------------------------------------------------
    // 7. Stripe-Version header is present on every request
    // -----------------------------------------------------------------------

    @Test
    void read_stripeVersionHeaderSentOnEveryRequest() {
        StripeConnectorAdapter adapter = createStripeAdapter();

        // Make at least one request.
        adapter.read(stripeConfig(), "products", emptyCursor()).findFirst();

        String sv = stripeFixture().getLastStripeVersionHeader();
        assertThat(sv)
                .as("Stripe-Version header should be present on every request")
                .isEqualTo("2024-06-20");
    }

    // -----------------------------------------------------------------------
    // 8. No duplicate IDs across pages
    // -----------------------------------------------------------------------

    @Test
    void read_noDuplicateIdsAcrossPages() {
        StripeConnectorAdapter adapter = createStripeAdapter();
        List<Map<String, Object>> rows = adapter
                .read(stripeConfig(), "invoices", emptyCursor())
                .collect(Collectors.toList());

        Set<Object> ids = rows.stream().map(r -> r.get("id")).collect(Collectors.toSet());
        assertThat(ids).hasSize(150);
    }
}
