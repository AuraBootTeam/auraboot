package com.auraboot.framework.bpm.extension;

import com.auraboot.framework.bpm.model.CcPolicy;
import com.auraboot.framework.bpm.model.WithdrawPolicy;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import com.auraboot.smart.framework.engine.model.assembly.ProcessDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Type-safe wrapper over SmartEngine's <smart:properties> extension parser.
 *
 * <p>SmartEngine parses <smart:properties> at deployment time and exposes the
 * result via {@code IdBasedElement.getProperties()} as {@code Map<String, String>}.
 * RepositoryQueryService caches parsed definitions in-memory, so this accessor
 * performs no IO and is safe to call on hot paths.
 *
 * <p>All AuraBoot business config keys are namespaced with the "aura." prefix
 * (see {@link BpmExtensionKeys}).
 */
@Component
@RequiredArgsConstructor
public class BpmExtensionAccessor {

    private final SmartEngine smartEngine;

    /** Get raw process-level property by exact key, or empty when not set. */
    public Optional<String> getProcessProperty(String processKey, String key) {
        ProcessDefinition def = findProcessDefinition(processKey);
        if (def == null) return Optional.empty();
        return readProperty(def.getProperties(), key);
    }

    /** Get raw activity-level property, or empty when activity or property absent. */
    public Optional<String> getActivityProperty(String processKey, String activityId, String key) {
        ProcessDefinition def = findProcessDefinition(processKey);
        if (def == null) return Optional.empty();
        IdBasedElement act = def.getIdBasedElementMap() == null
                ? null : def.getIdBasedElementMap().get(activityId);
        if (act == null) return Optional.empty();
        return readProperty(act.getProperties(), key);
    }

    /** Resolve effective WithdrawPolicy for the process, defaulting to STRICT. */
    public WithdrawPolicy getWithdrawPolicy(String processKey) {
        return getProcessProperty(processKey, BpmExtensionKeys.WITHDRAW_POLICY)
                .map(WithdrawPolicy::fromCode)
                .orElse(WithdrawPolicy.STRICT);
    }

    /**
     * Resolve effective CcPolicy: activity-level override (if any) takes
     * precedence over the process-level value; default is ALL.
     */
    public CcPolicy getCcPolicy(String processKey, String activityId) {
        if (activityId != null) {
            Optional<String> override = getActivityProperty(
                    processKey, activityId, BpmExtensionKeys.CC_POLICY_OVERRIDE);
            if (override.isPresent()) return CcPolicy.fromCode(override.get());
        }
        return getProcessProperty(processKey, BpmExtensionKeys.CC_POLICY)
                .map(CcPolicy::fromCode)
                .orElse(CcPolicy.ALL);
    }

    private ProcessDefinition findProcessDefinition(String processKey) {
        if (processKey == null || processKey.isBlank()) return null;
        return smartEngine.getRepositoryQueryService()
                .getAllCachedProcessDefinition()
                .stream()
                .filter(d -> processKey.equals(d.getId()))
                .findFirst()
                .orElse(null);
    }

    private Optional<String> readProperty(Map<String, String> properties, String key) {
        if (properties == null) return Optional.empty();
        String value = properties.get(key);
        return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value);
    }
}
