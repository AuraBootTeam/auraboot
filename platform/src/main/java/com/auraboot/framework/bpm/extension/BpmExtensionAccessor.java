package com.auraboot.framework.bpm.extension;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.model.CcPolicy;
import com.auraboot.framework.bpm.model.WithdrawPolicy;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.constant.ExtensionElementsConstant;
import com.auraboot.smart.framework.engine.model.assembly.ExtensionElementContainer;
import com.auraboot.smart.framework.engine.model.assembly.ExtensionElements;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import com.auraboot.smart.framework.engine.model.assembly.ProcessDefinition;
import com.auraboot.smart.framework.engine.smart.PropertyCompositeKey;
import com.auraboot.smart.framework.engine.smart.PropertyCompositeValue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Type-safe wrapper over SmartEngine's {@code <smart:properties>} extension parser.
 *
 * <p>SmartEngine parses {@code <smart:properties><smart:property name="..." value="..."/>}
 * at deployment time. The parsed entries are stored in:
 * <pre>
 *   element.getExtensionElements()
 *          .getDecorationMap()
 *          .get("Properties")      // Map&lt;PropertyCompositeKey, PropertyCompositeValue&gt;
 * </pre>
 *
 * <p><strong>Note:</strong> {@code IdBasedElement.getProperties()} holds XML element
 * <em>attributes</em> (id, version, isExecutable, …) — NOT the smart extension properties.
 * The extension properties live in the decorationMap under the "Properties" key.
 *
 * <p>All AuraBoot business config keys are namespaced with the "aura." prefix
 * (see {@link BpmExtensionKeys}).
 */
@Component
@RequiredArgsConstructor
public class BpmExtensionAccessor {

    private final SmartEngine smartEngine;

    /** Get raw process-level property from {@code <smart:properties>}, or empty when not set. */
    public Optional<String> getProcessProperty(String processKey, String key) {
        ProcessDefinition def = findProcessDefinition(processKey);
        if (def == null) return Optional.empty();
        return readExtensionProperty(def, key);
    }

    /**
     * Get raw activity-level property from {@code <smart:properties>}, or empty when absent.
     * Activity elements implement ExtensionElementContainer at runtime even though the
     * IdBasedElement interface does not declare it.
     */
    public Optional<String> getActivityProperty(String processKey, String activityId, String key) {
        ProcessDefinition def = findProcessDefinition(processKey);
        if (def == null) return Optional.empty();
        IdBasedElement act = def.getIdBasedElementMap() == null
                ? null : def.getIdBasedElementMap().get(activityId);
        if (!(act instanceof ExtensionElementContainer)) return Optional.empty();
        return readExtensionProperty((ExtensionElementContainer) act, key);
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

    /**
     * Find the deployed process definition for the given processKey under the current tenant.
     * Iterates all cached definitions; SmartEngine does not expose a direct key-only lookup
     * (the internal map key includes version and tenantId).
     */
    private ProcessDefinition findProcessDefinition(String processKey) {
        if (processKey == null || processKey.isBlank()) return null;
        String tenantId = MetaContext.getCurrentTenantIdAsString();
        return smartEngine.getRepositoryQueryService()
                .getAllCachedProcessDefinition()
                .stream()
                .filter(d -> processKey.equals(d.getId()))
                .filter(d -> tenantId == null
                        || d.getTenantId() == null
                        || tenantId.equals(d.getTenantId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Read a property value from the {@code <smart:properties>} extension elements of a
     * BPMN element (process or activity).
     *
     * <p>SmartEngine's {@link com.auraboot.smart.framework.engine.smart.Properties#decorate}
     * method stores each {@code <smart:property name="N" value="V"/>} as:
     * <ul>
     *   <li>key: {@code PropertyCompositeKey(type=null, name=N)}</li>
     *   <li>value: {@code PropertyCompositeValue(value=V, attrMap={...})}</li>
     * </ul>
     * in {@code extensionElements.decorationMap["Properties"]}.
     */
    @SuppressWarnings("unchecked")
    private Optional<String> readExtensionProperty(ExtensionElementContainer element, String propertyName) {
        ExtensionElements ext = element.getExtensionElements();
        if (ext == null) return Optional.empty();
        Map<String, Object> decorationMap = ext.getDecorationMap();
        if (decorationMap == null) return Optional.empty();
        Object propsObj = decorationMap.get(ExtensionElementsConstant.PROPERTIES);
        if (!(propsObj instanceof Map)) return Optional.empty();
        Map<PropertyCompositeKey, PropertyCompositeValue> props =
                (Map<PropertyCompositeKey, PropertyCompositeValue>) propsObj;
        // PropertyCompositeKey uses Lombok @Data equals/hashCode on (type, name).
        // BPMN <smart:property name="..." value="..."> produces type=null, name=key.
        PropertyCompositeValue compositeValue = props.get(new PropertyCompositeKey(null, propertyName));
        if (compositeValue == null) {
            // Single-arg constructor also sets type=null; try for defensive completeness.
            compositeValue = props.get(new PropertyCompositeKey(propertyName));
        }
        if (compositeValue == null) return Optional.empty();
        String value = compositeValue.getValue();
        return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value);
    }
}
