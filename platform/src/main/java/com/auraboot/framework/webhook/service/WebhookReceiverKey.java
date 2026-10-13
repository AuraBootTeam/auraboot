package com.auraboot.framework.webhook.service;

import java.net.URI;
import java.util.Locale;

/** Tenant-scoped receiver origin; paths and subscriptions share the same bulkhead. */
public final class WebhookReceiverKey {
    private WebhookReceiverKey() { }

    public static String of(String targetUrl, String subscriptionPid) {
        if (targetUrl == null) return "subscription:" + subscriptionPid;
        try {
            URI uri = URI.create(targetUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (!(scheme.equals("http") || scheme.equals("https")) || host == null || host.length() > 255)
                return "subscription:" + subscriptionPid;
            int port = uri.getPort() == -1 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();
            if (port < 1 || port > 65535) return "subscription:" + subscriptionPid;
            return scheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
        } catch (IllegalArgumentException invalid) {
            // Keep invalid legacy receivers independently drainable; transport validation rejects them.
            return "subscription:" + subscriptionPid;
        }
    }
}
