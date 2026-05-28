package com.auraboot.framework.plugin.extension.iot;

import java.time.Instant;
import java.util.List;

/**
 * Credential issue / revoke / ACL-sync bridge for plugin background
 * components — the IoT control plane plugin uses this to push device
 * credentials to the underlying broker (EMQX, NanoMQ, etc.) and to
 * reconcile MQTT topic ACL when device or product configuration changes.
 *
 * <p>Follows the same null-fallback SPI contract as the other
 * {@code Background*Accessor} interfaces.
 *
 * <p><b>Tenant isolation:</b> {@code tenantId} is required on every
 * mutating call; the platform implementation MUST refuse cross-tenant
 * device code references.
 *
 * <p><b>Secret handling:</b> {@link IotCredentials#secret()} returns the
 * raw secret only at issue time. Subsequent reads of the device row
 * receive the encrypted column value — plugins MUST NOT cache the secret
 * beyond what is needed to dispatch a single broker call.
 *
 * @since 2.6.0
 */
public interface BackgroundIotCredentialAccessor {

    /**
     * Issue (or rotate) credentials for a device and persist them on the
     * device row. Each call generates fresh material; callers needing the
     * previous secret must read it before invoking this method.
     *
     * @param tenantId   owning tenant id (must be {@code &gt; 0})
     * @param deviceCode tenant-unique device code (not blank)
     * @param type       credential kind to issue
     * @return freshly issued credentials; never null
     */
    IotCredentials issueCredentials(long tenantId, String deviceCode, CredentialType type);

    /**
     * Revoke all credentials for a device. The device row is marked
     * {@code DISABLE}; any active broker session is force-closed by the
     * subsequent ACL sync.
     *
     * @param tenantId   owning tenant id
     * @param deviceCode tenant-unique device code
     */
    void revokeCredentials(long tenantId, String deviceCode);

    /**
     * Trigger a full tenant-scope ACL push to the broker. This is the
     * canonical reconciliation path used after bulk device imports, product
     * ACL pattern changes, or broker restarts.
     *
     * <p>The call is synchronous: it returns once the broker accepts the
     * new ACL set or throws on rejection.
     *
     * @param tenantId owning tenant id
     */
    void syncAclToBroker(long tenantId);

    /**
     * Credential kind. Plugins SHOULD prefer {@link #ACCESS_TOKEN} unless
     * the broker / device toolchain mandates a specific scheme.
     */
    enum CredentialType {
        /** Long-lived bearer token issued by the platform. */
        ACCESS_TOKEN,
        /** X.509 client certificate (mutual TLS). */
        X509_CERTIFICATE,
        /** {@code username + password} pair scoped to MQTT basic auth. */
        MQTT_BASIC,
        /** Signed JWT (e.g. for short-lived broker auth). */
        JWT
    }

    /**
     * Immutable credential snapshot returned at issue time.
     *
     * @param type         credential kind
     * @param secret       raw secret (token / password / private key PEM); may
     *                     be null for {@link CredentialType#JWT}-only flows
     * @param jwt          signed JWT; populated only when {@code type == JWT};
     *                     null otherwise
     * @param aclPatterns  resolved MQTT topic ACL patterns the device may
     *                     publish / subscribe to; never null, may be empty
     * @param expiresAt    expiry timestamp; may be null for non-expiring
     *                     credentials (e.g. {@code ACCESS_TOKEN} without TTL)
     */
    record IotCredentials(
            CredentialType type,
            String secret,
            String jwt,
            List<String> aclPatterns,
            Instant expiresAt) {
    }
}
