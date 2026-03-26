package com.auraboot.framework.infrastructure.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Rewrites storage keys to CDN-based download URLs.
 * Only active when {@code aura.storage.cdn.base-url} is configured.
 */
@Component
@ConditionalOnProperty("aura.storage.cdn.base-url")
public class CdnUrlRewriter {

    private final String cdnBaseUrl;

    public CdnUrlRewriter(StorageProperties properties) {
        String url = properties.getCdn().getBaseUrl();
        this.cdnBaseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Rewrite a storage key to a CDN URL with proper encoding. */
    public String rewrite(String storageKey) {
        // Encode each path segment individually to preserve '/' separators
        String encodedKey = Arrays.stream(storageKey.split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
        return cdnBaseUrl + "/" + encodedKey;
    }
}
