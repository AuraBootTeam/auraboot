package com.auraboot.framework.integration;

import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Discovers one stable consumer identity across Core and PF4J plugins. */
@Component
@RequiredArgsConstructor
public class ReliableEventConsumerRegistry {

    private final AuraPluginManager pluginManager;
    private final ObjectProvider<ReliableEventConsumerExtension> coreConsumers;

    public List<ReliableEventConsumerExtension> consumersFor(String eventType) {
        Map<String, ReliableEventConsumerExtension> unique = new LinkedHashMap<>();
        Stream.concat(
                        pluginManager.getExtensionsOfType(ReliableEventConsumerExtension.class).stream(),
                        coreConsumers.stream())
                .filter(consumer -> consumer.supports(eventType))
                .sorted(Comparator.comparing(ReliableEventConsumerExtension::consumerCode))
                .forEach(consumer -> {
                    String code = consumer.consumerCode();
                    if (code == null || code.isBlank()) {
                        throw new IllegalStateException("Reliable event consumer code must not be blank");
                    }
                    ReliableEventConsumerExtension collision = unique.putIfAbsent(code, consumer);
                    if (collision != null && collision != consumer) {
                        throw new IllegalStateException("Duplicate reliable event consumer owner: " + code);
                    }
                });
        return List.copyOf(unique.values());
    }
}
