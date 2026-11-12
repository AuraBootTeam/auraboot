package com.auraboot.framework.plugin.extension.integration;

import org.pf4j.ExtensionPoint;
import java.util.Set;

/** Declares a fixed installed-integration command surface. No client-selected commands. */
public interface ExternalEventIngressExtension extends ExtensionPoint {
    Registration registration();

    /** Optional pure adapter for this provider's fixed business contract. No IO or side effects. */
    default java.util.Optional<BusinessRequest> prepareBusinessRequest(java.util.Map<String,Object> input) {
        return java.util.Optional.empty();
    }

    /** Internal normalized event; no routing fields are added to the public request. */
    record BusinessRequest(String eventType, String subjectType, String subjectPid,
                           java.time.Instant occurredAt, java.util.Map<String,Object> payload) {
        public BusinessRequest {
            if(eventType==null || !eventType.matches("external\\.[a-z0-9.-]+\\.v[1-9][0-9]*")
                    || subjectType==null || !subjectType.matches("[a-z][a-z0-9-]{1,63}")
                    || subjectPid==null || !subjectPid.matches("[A-Za-z0-9_.:-]{1,128}")
                    || occurredAt==null || payload==null)
                throw new IllegalArgumentException("Invalid business request identity");
            payload=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload));
        }
    }

    /** Explicit historical event sets allowed only for terminal receipt queries/replays.
     * No command, binding, permission or provider identity compatibility is implied. */
    default Set<Set<String>> completedReceiptEventTypeSets() { return Set.of(); }


    /** Explicit process-only model read grants added since an earlier completed receipt.
     * The host may remove only entire read-only grants when proving the old full command digest.
     * Accept/result commands, writes and binding identity remain pinned. Never applies to pending work.
     */
    default Set<String> completedReceiptProcessReadAdditions() { return Set.of(); }

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
