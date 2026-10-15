package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.plugin.extension.PublicApiHandlerExtension;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.*;

/** Spring core extensions and installed PF4J extensions share the same finite registry. */
@Component
@RequiredArgsConstructor
public class PublicApiHandlerRegistry {
    private final ObjectProvider<PublicApiHandlerExtension> core;
    private final ObjectProvider<AuraPluginManager> plugins;

    public List<PublicApiHandlerExtension> list() {
        List<PublicApiHandlerExtension> result = new ArrayList<>(core.orderedStream().toList());
        AuraPluginManager manager = plugins.getIfAvailable();
        if (manager != null) result.addAll(manager.getExtensions(PublicApiHandlerExtension.class));
        Map<String, PublicApiHandlerExtension> unique = new TreeMap<>();
        for (var handler : result) {
            if (unique.putIfAbsent(handler.definition().code(), handler) != null)
                throw new IllegalStateException("Duplicate public handler registration");
        }
        return List.copyOf(unique.values());
    }
    public PublicApiHandlerExtension require(String code) {
        return list().stream().filter(handler -> handler.definition().code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Public handler is not registered"));
    }
}
