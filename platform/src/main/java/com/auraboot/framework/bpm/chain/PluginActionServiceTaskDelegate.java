package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.extension.ServiceTaskActionExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.delegation.JavaDelegation;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * SmartEngine serviceTask delegate that invokes a plugin {@link ServiceTaskActionExtension}.
 *
 * <p>This is the non-command counterpart to {@link CommandServiceTaskDelegate}. Command-shaped
 * serviceTasks reach plugin behaviour through {@code commandServiceTaskDelegate → CommandExecutor →
 * CommandHandlerExtension}; this delegate reaches plugin behaviour for actions that genuinely
 * cannot be modelled as an AuraBoot command, via
 * {@code pluginActionServiceTaskDelegate → ExtensionRegistry → ServiceTaskActionExtension}.
 *
 * <p>Wired into BPMN via {@code smart:class="pluginActionServiceTaskDelegate"}. The node XML
 * carries:
 * <ul>
 *   <li>{@code smart:action} — the action type resolved against
 *       {@link ServiceTaskActionExtension#supports(String)} (required).</li>
 *   <li>{@code smart:resultVar} — optional process-variable name to receive the action's
 *       return value (in addition to the always-written {@code _action_<id>_result}).</li>
 *   <li>any other {@code smart:*} attribute — passed through to the extension as a property.</li>
 * </ul>
 *
 * <p>Fail-fast: an unknown action throws (no silent skip), so a misconfigured BPMN surfaces at
 * deploy/run rather than silently doing nothing.
 *
 * @since 7.4.0
 */
@Slf4j
@Component(BpmServiceTaskConstants.BEAN_PLUGIN_ACTION_DELEGATE)
@RequiredArgsConstructor
public class PluginActionServiceTaskDelegate implements JavaDelegation {

    public static final String ERR_ACTION_REQUIRED = "bpm.action.action_required";
    public static final String ERR_ACTION_UNRESOLVED = "bpm.action.action_unresolved";
    public static final String ERR_ACTION_FAILED = "bpm.action.action_failed";

    private final ExtensionRegistry extensionRegistry;

    @Override
    public void execute(ExecutionContext executionContext) {
        Map<String, Object> processVars = executionContext.getRequest();
        if (processVars == null) {
            processVars = new HashMap<>();
            executionContext.setRequest(processVars);
        }

        Map<String, String> properties = resolveProperties(executionContext);

        String actionType = properties.get(BpmServiceTaskConstants.ATTR_ACTION);
        if (actionType == null || actionType.isBlank()) {
            throw new BusinessException(ERR_ACTION_REQUIRED);
        }

        Optional<ServiceTaskActionExtension> extension = extensionRegistry.getServiceTaskAction(actionType);
        if (extension.isEmpty()) {
            log.error("No ServiceTaskActionExtension registered for smart:action='{}' (node {})",
                    actionType, resolveActivityId(executionContext));
            throw new BusinessException(ERR_ACTION_UNRESOLVED);
        }

        // Tenant is best-effort: a BPM execution thread normally carries MetaContext, but the
        // extension can also read tenant from process variables, so a missing context must not
        // blow up the step (ActionContext.tenantId is documented nullable).
        Long tenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;

        ServiceTaskActionExtension.ActionContext context = ServiceTaskActionExtension.ActionContext.builder()
                .tenantId(tenantId)
                .actionType(actionType)
                .variables(processVars)
                .properties(properties)
                .build();

        String activityId = resolveActivityId(executionContext);
        try {
            Object result = extension.get().execute(context);
            processVars.put("_action_" + activityId + "_success", true);
            if (result != null) {
                processVars.put("_action_" + activityId + "_result", result);
                String resultVar = properties.get(BpmServiceTaskConstants.ATTR_RESULT_VAR);
                if (resultVar != null && !resultVar.isBlank()) {
                    processVars.put(resultVar, result);
                }
            }
            log.info("Plugin action serviceTask completed: action={}, node={}", actionType, activityId);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("Plugin action serviceTask failed: action={}, node={}, error={}",
                    actionType, activityId, e.getMessage(), e);
            throw new BusinessException(ERR_ACTION_FAILED);
        }
    }

    private Map<String, String> resolveProperties(ExecutionContext executionContext) {
        if (executionContext.getBaseElement() instanceof IdBasedElement idBased
                && idBased.getProperties() != null) {
            return idBased.getProperties();
        }
        return new HashMap<>();
    }

    private String resolveActivityId(ExecutionContext executionContext) {
        if (executionContext.getBaseElement() instanceof IdBasedElement idBased) {
            return idBased.getId();
        }
        return "bpm:plugin-action";
    }
}
