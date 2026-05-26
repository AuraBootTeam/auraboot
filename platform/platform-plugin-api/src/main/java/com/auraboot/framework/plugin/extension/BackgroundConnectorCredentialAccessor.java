package com.auraboot.framework.plugin.extension;

import java.util.Map;
import java.util.Optional;

/**
 * Credential-lookup bridge for plugin background components that need to
 * fetch with credentials from a configured API connector without coupling
 * to the platform-internal {@code ApiConnectorService} type.
 *
 * <p>Returns the connector's base URL, auth type, raw auth-config JSON,
 * and any default headers — enough for a plugin to attach Authorization
 * / cookies / static headers to outbound fetch requests. The plugin
 * remains responsible for parsing auth-type-specific config (basic,
 * bearer, api_key, custom).
 *
 * <p>Tenant isolation: the platform impl resolves by {@code pid}, which
 * is globally unique, so callers should still ensure the pid they hand
 * over came from their tenant scope (e.g. via a tenant-scoped
 * {@link BackgroundDataAccessor} read).
 *
 * @since 2.5.0
 */
public interface BackgroundConnectorCredentialAccessor {

    /**
     * @param connectorPid the ApiConnector.pid (VARCHAR(32) unique).
     * @return credentials snapshot, or empty if no connector matches.
     */
    Optional<ConnectorCredentials> lookupByPid(String connectorPid);

    /**
     * Immutable snapshot of a connector's outward-facing credentials. No
     * setters — plugins compose new fetch headers from these fields.
     */
    final class ConnectorCredentials {
        private final String pid;
        private final String baseUrl;
        private final String authType;
        private final String authConfigJson;
        private final Map<String, String> defaultHeaders;

        public ConnectorCredentials(String pid,
                                    String baseUrl,
                                    String authType,
                                    String authConfigJson,
                                    Map<String, String> defaultHeaders) {
            this.pid = pid;
            this.baseUrl = baseUrl;
            this.authType = authType;
            this.authConfigJson = authConfigJson;
            this.defaultHeaders = defaultHeaders;
        }

        public String getPid() { return pid; }
        public String getBaseUrl() { return baseUrl; }
        public String getAuthType() { return authType; }
        /** Raw JSON; plugin parses per auth-type. May be null. */
        public String getAuthConfigJson() { return authConfigJson; }
        /** Empty map when none configured (never null). */
        public Map<String, String> getDefaultHeaders() { return defaultHeaders; }
    }
}
