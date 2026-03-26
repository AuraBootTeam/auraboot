package com.auraboot.framework.plugin.extension;

import org.pf4j.ExtensionPoint;

import java.util.List;
import java.util.Map;

/**
 * Extension point for data providers.
 * Plugins can implement this interface to provide data for dropdowns, lookups, or queries.
 *
 * Example usage:
 * <pre>
 * {@code
 * @Extension
 * public class CurrencyDataProvider implements DataProviderExtension {
 *     @Override
 *     public String getProviderKey() {
 *         return "billing:currencies";
 *     }
 *
 *     @Override
 *     public List<DataItem> fetchData(DataRequest request) {
 *         return List.of(
 *             new DataItem("usd", "US Dollar", Map.of("symbol", "$")),
 *             new DataItem("eur", "Euro", Map.of("symbol", "€"))
 *         );
 *     }
 * }
 * }
 * </pre>
 */
public interface DataProviderExtension extends ExtensionPoint {

    /**
     * Get the unique key for this data provider.
     * Format: "namespace:provider-name" (e.g., "billing:currencies", "hr:departments")
     *
     * @return provider key
     */
    String getProviderKey();

    /**
     * Fetch data based on the request.
     *
     * @param request data request containing filters and pagination
     * @return list of data items
     */
    List<DataItem> fetchData(DataRequest request);

    /**
     * Get the total count of items matching the request.
     * Used for pagination.
     *
     * @param request data request containing filters
     * @return total count
     */
    default long getCount(DataRequest request) {
        return fetchData(request).size();
    }

    /**
     * Check if this provider supports the given key.
     *
     * @param providerKey the key to check
     * @return true if this provider can handle the request
     */
    default boolean supports(String providerKey) {
        return getProviderKey().equals(providerKey);
    }

    /**
     * Whether this provider supports caching.
     * Default is true.
     *
     * @return true if results can be cached
     */
    default boolean isCacheable() {
        return true;
    }

    /**
     * Get cache TTL in seconds.
     * Only used if isCacheable() returns true.
     * Default is 300 (5 minutes).
     *
     * @return cache TTL in seconds
     */
    default int getCacheTtlSeconds() {
        return 300;
    }

    /**
     * Data item returned by the provider.
     */
    record DataItem(
            String value,
            String label,
            Map<String, Object> metadata
    ) {
        public DataItem(String value, String label) {
            this(value, label, Map.of());
        }
    }

    /**
     * Data request containing filters and pagination.
     */
    record DataRequest(
            Long tenantId,
            String pluginId,
            String namespace,
            String providerKey,
            String searchTerm,
            Map<String, Object> filters,
            int offset,
            int limit,
            Map<String, Object> settings
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Long tenantId;
            private String pluginId;
            private String namespace;
            private String providerKey;
            private String searchTerm;
            private Map<String, Object> filters = Map.of();
            private int offset = 0;
            private int limit = 100;
            private Map<String, Object> settings = Map.of();

            public Builder tenantId(Long tenantId) {
                this.tenantId = tenantId;
                return this;
            }

            public Builder pluginId(String pluginId) {
                this.pluginId = pluginId;
                return this;
            }

            public Builder namespace(String namespace) {
                this.namespace = namespace;
                return this;
            }

            public Builder providerKey(String providerKey) {
                this.providerKey = providerKey;
                return this;
            }

            public Builder searchTerm(String searchTerm) {
                this.searchTerm = searchTerm;
                return this;
            }

            public Builder filters(Map<String, Object> filters) {
                this.filters = filters;
                return this;
            }

            public Builder offset(int offset) {
                this.offset = offset;
                return this;
            }

            public Builder limit(int limit) {
                this.limit = limit;
                return this;
            }

            public Builder settings(Map<String, Object> settings) {
                this.settings = settings;
                return this;
            }

            public DataRequest build() {
                return new DataRequest(tenantId, pluginId, namespace, providerKey, searchTerm, filters, offset, limit, settings);
            }
        }
    }
}
