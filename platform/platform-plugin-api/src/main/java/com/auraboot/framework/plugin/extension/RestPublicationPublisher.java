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

    /** Bind a fixed command to the authenticated publisher and the private configuration. */
    default void publishWithCommand(String kind, String id, String parentKind, String parentId,
                                    Map<String, Object> document, List<String> origins,
                                    String commandCode, Map<String, Object> input) {
        throw new UnsupportedOperationException("Publication commands are unavailable");
    }

    /** Publish an integration command protected by a server-held digest of its API key. */
    default void publishKeyedCommand(String kind, String id, String key, String commandCode,
                                     Map<String, Object> input) {
        throw new UnsupportedOperationException("Keyed publication commands are unavailable");
    }

    void withdraw(String kind, String id);
}
