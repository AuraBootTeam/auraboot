package com.auraboot.framework.plugin.extension.integration;

import org.pf4j.ExtensionPoint;
import java.util.Set;

/** Declares a fixed installed-integration command surface. No client-selected commands. */
public interface ExternalEventIngressExtension extends ExtensionPoint {
    Registration registration();

    record Registration(String sourceCode, String capability, Set<String> eventTypes,
                        String requiredScope, String commandPermission,
                        String providerPluginId, String commandOwnerPluginId,
                        String acceptCommand, String processCommand, String resultCommand, String serverActorParameter) {
        public Registration(String sourceCode, String capability, Set<String> eventTypes, String requiredScope,
                String commandPermission, String providerPluginId, String commandOwnerPluginId,
                String acceptCommand, String processCommand, String resultCommand) {
            this(sourceCode,capability,eventTypes,requiredScope,commandPermission,providerPluginId,
                    commandOwnerPluginId,acceptCommand,processCommand,resultCommand,null);
        }
        public Registration {
            if (serverActorParameter != null && !serverActorParameter.matches("[a-z][a-zA-Z0-9]{0,63}"))
                throw new IllegalArgumentException("Invalid server actor parameter");
            eventTypes = Set.copyOf(eventTypes);
            if (sourceCode == null || !sourceCode.matches("[a-z][a-z0-9-]{2,63}")
                    || capability == null || !capability.matches("[a-z][a-z0-9.-]{2,100}")
                    || eventTypes.isEmpty() || eventTypes.size() > 16
                    || eventTypes.stream().anyMatch(value -> !value.startsWith("external."+sourceCode+".") || !value.matches("external\\.[a-z0-9.-]+\\.v[1-9][0-9]*"))
                    || requiredScope == null || requiredScope.isBlank()
                    || commandPermission == null || commandPermission.isBlank()
                    || providerPluginId == null || providerPluginId.isBlank()
                    || commandOwnerPluginId == null || commandOwnerPluginId.isBlank()
                    || Set.of(acceptCommand, processCommand, resultCommand).size() != 3)
                throw new IllegalArgumentException("Invalid installed integration registration");
            for (String command : Set.of(acceptCommand, processCommand, resultCommand))
                if (!command.matches("[a-z][a-z0-9_]*:[a-z][a-z0-9_]*") || command.length() > 64)
                    throw new IllegalArgumentException("Invalid installed integration command");
        }
    }
}
