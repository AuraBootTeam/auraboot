package com.auraboot.framework.infrastructure.storage;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Rewrites storage keys to CDN-based download URLs.
 * Only active when {@code aura.storage.cdn.base-url} is configured.
 */
@Component
@Conditional(CdnUrlRewriter.HasCdnBaseUrl.class)
public class CdnUrlRewriter {

    /** An empty YAML placeholder must not override authenticated file download URLs. */
    public static final class HasCdnBaseUrl implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment().getProperty("aura.storage.cdn.base-url"));
        }
    }

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
