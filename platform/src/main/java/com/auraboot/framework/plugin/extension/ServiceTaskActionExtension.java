package com.auraboot.framework.plugin.extension;

import org.pf4j.ExtensionPoint;

import java.util.Map;
import java.util.Set;

/**
 * Extension point for non-command BPM serviceTask actions.
 *
 * <p>Command-shaped serviceTask actions already reach plugin behaviour through
 * {@code commandServiceTaskDelegate → CommandExecutor → } {@link CommandHandlerExtension}.
 * This is the symmetric extension point for serviceTask actions a plugin wants to run
 * <i>directly</i> from a BPMN — work that genuinely cannot be modelled as an AuraBoot
 * command (e.g. invoking an external device protocol, computing an in-flight value, or
 * driving a side-effecting integration that has no command contract).
 *
 * <p>The host {@code pluginActionServiceTaskDelegate} resolves the action for a serviceTask
 * carrying {@code smart:class="pluginActionServiceTaskDelegate"} and
 * {@code smart:action="<actionType>"}, looks up the highest-priority extension whose
 * {@link #supports(String)} matches, and invokes {@link #execute(ActionContext)}. The
 * returned value (if any) is written back to a process variable so downstream nodes can read it.
 *
 * <p>Example usage:
 * <pre>
 * {@code
 * @Extension
 * public class RecalibrateSensorAction implements ServiceTaskActionExtension {
 *     @Override
 *     public String getActionType() {
 *         return "iot:recalibrate-sensor";
 *     }
 *
 *     @Override
 *     public Object execute(ActionContext ctx) {
 *         String deviceId = String.valueOf(ctx.variables().get("deviceId"));
 *         // ... drive the device protocol ...
 *         return Map.of("calibrated", true);
 *     }
 * }
 * }
 * </pre>
 *
 * @since 7.4.0
 */
public interface ServiceTaskActionExtension extends ExtensionPoint {

    /**
     * The action type this extension handles.
     * Format: {@code "namespace:action-name"} (e.g. {@code "iot:recalibrate-sensor"}).
     *
     * @return action type identifier
     */
    String getActionType();

    /**
     * Enumerate all action types this extension can process. Defaults to the single
     * {@link #getActionType()}; override together with {@link #supports(String)} when an
     * extension handles aliases.
     *
     * @return supported action type identifiers
     */
    default Set<String> getSupportedActionTypes() {
        String actionType = getActionType();
        return actionType == null ? Set.of() : Set.of(actionType);
    }

    /**
     * Execute the action.
     *
     * @param context the action execution context (tenant, action type, live process
     *                variables, serviceTask {@code smart:*} properties)
     * @return an optional result written back to a process variable; may be {@code null}
     * @throws Exception if execution fails (propagated by the delegate to abort/roll back the step)
     */
    Object execute(ActionContext context) throws Exception;

    /**
     * Whether this extension supports the given action type.
     *
     * @param actionType the action type to check
     * @return {@code true} if this extension can process the action
     */
    default boolean supports(String actionType) {
        return getActionType() != null && getActionType().equals(actionType);
    }

    /**
     * Priority of this extension. Higher priority wins when several extensions support the
     * same action type. Default is {@code 0}.
     *
     * @return extension priority
     */
    default int getPriority() {
        return 0;
    }

    /**
     * ServiceTask action execution context.
     *
     * @param tenantId   current tenant (resolved from the BPM execution thread; may be {@code null})
     * @param pluginId   the plugin id owning the resolved extension (may be {@code null} for core beans)
     * @param actionType the resolved {@code smart:action} value
     * @param variables  the live process variables map — readable, and writable for setting
     *                   variables that downstream nodes consume
     * @param properties the serviceTask's {@code smart:*} extension attributes (name → value)
     */
    record ActionContext(
            Long tenantId,
            String pluginId,
            String actionType,
            Map<String, Object> variables,
            Map<String, String> properties
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Long tenantId;
            private String pluginId;
            private String actionType;
            private Map<String, Object> variables;
            private Map<String, String> properties;

            public Builder tenantId(Long tenantId) {
                this.tenantId = tenantId;
                return this;
            }

            public Builder pluginId(String pluginId) {
                this.pluginId = pluginId;
                return this;
            }

            public Builder actionType(String actionType) {
                this.actionType = actionType;
                return this;
            }

            public Builder variables(Map<String, Object> variables) {
                this.variables = variables;
                return this;
            }

            public Builder properties(Map<String, String> properties) {
                this.properties = properties;
                return this;
            }

            public ActionContext build() {
                return new ActionContext(tenantId, pluginId, actionType, variables, properties);
            }
        }
    }
}
