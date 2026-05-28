package com.auraboot.framework.iot.broker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Broker connection tunables for {@link EmqxAclSyncService}.
 *
 * @since 2.6.0
 */
@ConfigurationProperties("iot.emqx")
public class EmqxAclProperties {

    /** Broker HTTP management base URL, e.g. {@code http://emqx:18083}. */
    private String baseUrl = "";

    /** API key (HTTP basic username). */
    private String apiKey = "";

    /** API secret (HTTP basic password). */
    private String apiSecret = "";

    /** Authenticator id pre-created on the broker (JWT auth chain). */
    private String authenticatorId = "password_based:built_in_database";

    /** Connect / read timeout for broker calls. */
    private int timeoutMs = 5_000;

    /** Whether sync is enabled. Off by default so unit tests / OSS profiles don't try to reach a broker. */
    private boolean enabled = false;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
    public String getAuthenticatorId() { return authenticatorId; }
    public void setAuthenticatorId(String authenticatorId) { this.authenticatorId = authenticatorId; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
