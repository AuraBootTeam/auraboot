package com.auraboot.framework.plugin.extension;

import java.util.List;
import java.util.Map;

/** Publishes explicit public data within an authenticated REST request's tenant and namespace. */
public interface RestPublicationPublisher {
    void publish(String kind, String id, String parentKind, String parentId, Map<String, Object> document);
    /** Stores browser origins separately from the document exposed to the public read handler. */
    default void publishWithOrigins(String kind, String id, String parentKind, String parentId,
                                    Map<String, Object> document, List<String> origins) {
        throw new UnsupportedOperationException("Publication origins are unavailable");
    }

    void withdraw(String kind, String id);
}
