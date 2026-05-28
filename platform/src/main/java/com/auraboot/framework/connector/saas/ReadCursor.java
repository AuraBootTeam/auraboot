package com.auraboot.framework.connector.saas;

import java.time.Instant;
import java.util.Map;

/**
 * Incremental-sync cursor passed to
 * {@link AbstractSaasConnectorAdapter#read(SaasConnectorConfig, String, ReadCursor)}.
 *
 * <p>All three fields are optional — a {@code null} cursor means "full snapshot".
 * The vendor adapter decides which field is meaningful (Salesforce uses {@code since}
 * against {@code SystemModstamp}; HubSpot uses {@code pageToken} for {@code ?after};
 * Stripe uses {@code pageToken} for {@code ?starting_after}; Shopify uses {@code pageToken}
 * for the {@code page_info} Link header; DingTalk uses {@code customState} for offset+size).
 *
 * @param since        starting timestamp for incremental sync; null = from origin
 * @param pageToken    opaque pagination token returned by the vendor; null = first page
 * @param customState  vendor-specific JSON-shaped state bag for resume
 * @since 5.3.0
 */
public record ReadCursor(
        Instant since,
        String pageToken,
        Map<String, Object> customState
) {
    public ReadCursor {
        customState = customState == null ? Map.of() : Map.copyOf(customState);
    }

    public static ReadCursor empty() {
        return new ReadCursor(null, null, Map.of());
    }
}
