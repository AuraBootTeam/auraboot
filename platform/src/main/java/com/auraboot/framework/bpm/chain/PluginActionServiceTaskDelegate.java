package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.service.ExecutionLogService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.extension.ServiceTaskActionExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.delegation.JavaDelegation;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private final ExecutionLogService executionLogService;

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
        long startedAtNanos = System.nanoTime();
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
            Map<String, Object> success = successPayload(actionType, result);
            logActionSuccess(executionContext, activityId, actionType, properties, success,
                    (System.nanoTime() - startedAtNanos) / 1_000_000);
            log.info("Plugin action serviceTask completed: action={}, node={}", actionType, activityId);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("Plugin action serviceTask failed: action={}, node={}, error={}",
                    actionType, activityId, e.getMessage(), e);
            Map<String, Object> failure = writeFailureVars(processVars, activityId, actionType, properties, e);
            logActionFailure(executionContext, activityId, actionType, properties, failure, e);
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, ERR_ACTION_FAILED, e);
        }
    }

    private void logActionSuccess(ExecutionContext executionContext,
                                  String activityId,
                                  String actionType,
                                  Map<String, String> properties,
                                  Map<String, Object> success,
                                  long durationMs) {
        String executionId = resolveExecutionId(executionContext);
        if (executionId == null || executionId.isBlank()) {
            return;
        }

        Map<String, Object> context = baseActionContext(executionContext, actionType, "SUCCESS");
        String resultVar = properties.get(BpmServiceTaskConstants.ATTR_RESULT_VAR);
        if (resultVar != null && !resultVar.isBlank()) {
            context.put("resultVar", resultVar);
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("action", success);
        try {
            executionLogService.logActionExecuted(executionId, activityId, context, output, durationMs);
        } catch (Exception logError) {
            log.warn("Failed to record plugin action serviceTask success: executionId={}, action={}, node={}, error={}",
                    executionId, actionType, activityId, logError.getMessage(), logError);
        }
    }

    private Map<String, Object> writeFailureVars(Map<String, Object> processVars,
                                                 String activityId,
                                                 String actionType,
                                                 Map<String, String> properties,
                                                 Exception error) {
        Map<String, Object> result = failurePayload(actionType, error);
        processVars.put("_action_" + activityId + "_success", false);
        processVars.put("_action_" + activityId + "_error", result.get("error"));
        processVars.put("_action_" + activityId + "_result", result);
        String resultVar = properties.get(BpmServiceTaskConstants.ATTR_RESULT_VAR);
        if (resultVar != null && !resultVar.isBlank()) {
            processVars.put(resultVar, result);
        }
        return result;
    }

    private void logActionFailure(ExecutionContext executionContext,
                                  String activityId,
                                  String actionType,
                                  Map<String, String> properties,
                                  Map<String, Object> failure,
                                  Exception error) {
        String executionId = resolveExecutionId(executionContext);
        if (executionId == null || executionId.isBlank()) {
            return;
        }

        Map<String, Object> context = baseActionContext(executionContext, actionType, "FAILED");
        context.put("action", failure);
        String resultVar = properties.get(BpmServiceTaskConstants.ATTR_RESULT_VAR);
        if (resultVar != null && !resultVar.isBlank()) {
            context.put("resultVar", resultVar);
        }
        try {
            executionLogService.logNodeFailure(executionId, activityId, error, context);
        } catch (Exception logError) {
            log.warn("Failed to record plugin action serviceTask failure: executionId={}, action={}, node={}, error={}",
                    executionId, actionType, activityId, logError.getMessage(), logError);
        }
    }

    private Map<String, Object> baseActionContext(ExecutionContext executionContext,
                                                  String actionType,
                                                  String status) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("actionType", actionType);
        context.put("status", status);
        putIfPresent(context, "processKey", resolveProcessKey(executionContext));
        putIfPresent(context, "businessKey",
                stringify(processVars(executionContext).get(RequestMapSpecialKeyConstant.PROCESS_BIZ_UNIQUE_ID)));
        putIfPresent(context, "startUserId",
                stringify(processVars(executionContext).get(RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID)));
        putIfPresent(context, "tenantId",
                stringify(processVars(executionContext).get(RequestMapSpecialKeyConstant.TENANT_ID)));
        return context;
    }

    private Map<String, Object> successPayload(String actionType, Object result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (result instanceof Map<?, ?> map) {
            map.forEach((key, value) -> payload.put(String.valueOf(key), value));
        } else if (result != null) {
            payload.put("result", result);
        }
        payload.put("status", "SUCCESS");
        payload.put("actionType", actionType);
        return Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    private Map<String, Object> failurePayload(String actionType, Exception error) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (error instanceof ActionExecutionException actionFailure && actionFailure.resultPayload() != null) {
            result.putAll(actionFailure.resultPayload());
        }
        result.put("status", "FAILED");
        result.put("actionType", actionType);
        result.put("error", error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName());
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
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

    private String resolveProcessKey(ExecutionContext executionContext) {
        if (executionContext.getProcessDefinition() != null) {
            return executionContext.getProcessDefinition().getId();
        }
        if (executionContext.getProcessInstance() != null) {
            return executionContext.getProcessInstance().getProcessDefinitionId();
        }
        return null;
    }

    private String resolveExecutionId(ExecutionContext executionContext) {
        if (executionContext.getProcessInstance() != null) {
            return executionContext.getProcessInstance().getInstanceId();
        }
        if (executionContext.getExecutionInstance() != null) {
            return executionContext.getExecutionInstance().getInstanceId();
        }
        return null;
    }

    private Map<String, Object> processVars(ExecutionContext executionContext) {
        Map<String, Object> request = executionContext.getRequest();
        return request == null ? Collections.emptyMap() : request;
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String stringify(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }
}
