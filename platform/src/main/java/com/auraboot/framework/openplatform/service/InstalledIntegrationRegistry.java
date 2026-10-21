package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.plugin.extension.integration.ExternalEventIngressExtension;
import com.auraboot.framework.plugin.extension.integration.ExternalEventIngressExtension.Registration;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@RequiredArgsConstructor
public class InstalledIntegrationRegistry {
    private final AuraPluginManager plugins;
    public List<Registration> registrations() {
        Set<String> sources = new HashSet<>();
        for (var extension : plugins.getExtensionsOfType(ExternalEventIngressExtension.class)) sources.add(extension.registration().sourceCode());
        return sources.stream().sorted().map(source -> registration(source).orElseThrow().getKey()).toList();
    }
    public Optional<Map.Entry<Registration,String>> registration(String source) {
        List<Map.Entry<Registration,String>> matches = new ArrayList<>();
        // Validate the exact object obtained from this discovery call, not a second factory instance.
        for (ExternalEventIngressExtension extension : plugins.getExtensionsOfType(ExternalEventIngressExtension.class)) {
            Registration registration = Objects.requireNonNull(extension.registration());
            if (!registration.sourceCode().equals(source)) continue;
            var artifact = plugins.observeLoadedArtifact(extension.getClass()).orElseThrow(InstalledIntegrationRegistry::denied);
            if (!registration.providerPluginId().equals(artifact.pluginId())) throw denied();
            matches.add(Map.entry(registration, artifact.digest()));
        }
        if (matches.size() > 1) throw new SecurityException("Duplicate installed integration source");
        return matches.stream().findFirst();
    }

    public Set<Registration> completedReceiptRegistrations(String source) {
        var current = registration(source).orElseThrow(InstalledIntegrationRegistry::denied).getKey();
        var matches = plugins.getExtensionsOfType(ExternalEventIngressExtension.class).stream()
                .filter(extension -> source.equals(extension.registration().sourceCode())).toList();
        if (matches.size() != 1 || !current.equals(matches.getFirst().registration())) throw denied();
        var sets = Objects.requireNonNull(matches.getFirst().completedReceiptEventTypeSets());
        if (sets.size() > 4) throw denied();
        Set<Registration> result = new HashSet<>();
        for (var events : sets) {
            if (events == null || events.isEmpty() || events.equals(current.eventTypes())
                    || !current.eventTypes().containsAll(events)) throw denied();
            result.add(new Registration(current.sourceCode(), current.capability(), events,
                    current.requiredScope(), current.commandPermission(), current.providerPluginId(),
                    current.commandOwnerPluginId(), current.acceptCommand(), current.processCommand(),
                    current.resultCommand(), current.serverActorParameter()));
        }
        return Set.copyOf(result);
    }

    private static SecurityException denied() { return new SecurityException("Installed integration provider denied"); }
}
