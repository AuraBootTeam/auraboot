package com.auraboot.framework.plugin.extension;

import java.util.Map;

/** Publishes explicit public data within an authenticated REST request's tenant and namespace. */
public interface RestPublicationPublisher {
    void publish(String kind, String id, String parentKind, String parentId, Map<String, Object> document);
    void withdraw(String kind, String id);
}
