package com.auraboot.framework.meta.event;

import org.springframework.context.ApplicationEvent;

/**
 * Spring Application Event published when a page schema is published.
 * Can be consumed by listeners to trigger mobile schema sync notifications, cache invalidation, etc.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
public class SchemaPublishedEvent extends ApplicationEvent {

    private final String pageKey;
    private final int version;

    public SchemaPublishedEvent(Object source, String pageKey, int version) {
        super(source);
        this.pageKey = pageKey;
        this.version = version;
    }

    public String getPageKey() {
        return pageKey;
    }

    public int getVersion() {
        return version;
    }
}
