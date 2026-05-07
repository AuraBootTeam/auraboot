package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.service.impl.CommandCascadeDeleteExecutor;
import com.auraboot.framework.meta.service.impl.CommandFieldMapExecutor;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.RecordSnapshotReader;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@Order(1000)
@RequiredArgsConstructor
public class FieldMapPhase implements CommandPhase {

    private final CommandFieldMapExecutor fieldMapExecutor;
    private final CommandCascadeDeleteExecutor cascadeDeleteExecutor;
    private final RecordSnapshotReader snapshotReader;
    private final ExtensionRegistry extensionRegistry;

    @Override
    public String name() {
        return "field_map";
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        // Before-snapshot for change tracking
        if (ctx.getRequest() != null && StringUtils.hasText(ctx.getRequest().getTargetRecordId())
                && StringUtils.hasText(ctx.getCommand().getModelCode())) {
            ctx.setBeforeSnapshot(snapshotReader.readRecordSnapshot(
                    ctx.getTenantId(), ctx.getCommand().getModelCode(), ctx.getRequest().getTargetRecordId()));
        }

        // Cascade delete
        if ("delete".equalsIgnoreCase(ctx.getRequest().getOperationType())) {
            cascadeDeleteExecutor.executeCascadeDeletePhase(ctx.getExecConfig(), ctx.getTenantId(), ctx.getRequest());
        }

        // Field map
        Map<String, Object> fieldMapResults;
        if (isPluginHandledWithoutDslPersistence(ctx)) {
            fieldMapResults = new HashMap<>();
            log.info("Skipping FIELD_MAP for plugin-handled command: {}", ctx.getCommand().getCode());
        } else {
            List<BindingRule> fieldMapRules = ctx.getRulesByType().getOrDefault("field_map", Collections.emptyList());
            boolean noBindingRules = fieldMapRules.isEmpty();
            Map<String, Object> ec = ctx.getExecConfig();
            boolean hasInputFields = ec.containsKey("inputFields");
            boolean hasAutoSetFields = ec.containsKey("autoSetFields");
            String cmdType = (String) ec.get("type");
            // isDeleteOp must mirror isStateTransition/isCreateOrUpdate semantics:
            // fall back to command.type when request.operationType is empty.
            // Without this fallback, `type: "delete"` commands invoked via
            // CLI/API without an explicit `--operation delete` skip the
            // implicit field-map path entirely, hit the empty-binding-rules
            // branch in executeFieldMapPhase, and silently no-op while
            // returning phaseReached=completed. Same for `--target` flows.
            boolean isDeleteOp = "delete".equalsIgnoreCase(ctx.getRequest().getOperationType())
                    || "delete".equalsIgnoreCase(cmdType);
            boolean isStateTransition = "state_transition".equalsIgnoreCase(cmdType);
            boolean isCreateOrUpdate = "create".equalsIgnoreCase(cmdType) || "update".equalsIgnoreCase(cmdType);

            if (noBindingRules && (hasInputFields || hasAutoSetFields || isDeleteOp || isStateTransition || isCreateOrUpdate)) {
                fieldMapResults = fieldMapExecutor.executeImplicitFieldMapPhase(
                        ec, ctx.getPayload(), ctx.getTenantId(), ctx.getRequest(), ctx.getCommand());
            } else {
                fieldMapResults = fieldMapExecutor.executeFieldMapPhase(
                        fieldMapRules, ctx.getPayload(), ctx.getTenantId(), ctx.getRequest());
            }
        }
        ctx.setFieldMapResults(fieldMapResults);

        // Propagate record ID from fieldMapResults to request (inline)
        propagateFieldMapRecordId(ctx.getRequest(), fieldMapResults);
    }

    private void propagateFieldMapRecordId(CommandExecuteRequest request, Map<String, Object> fieldMapResults) {
        if (request == null || fieldMapResults == null || StringUtils.hasText(request.getTargetRecordId())) {
            return;
        }
        Object recordId = fieldMapResults.get("recordId");
        if (recordId instanceof String recordIdStr && StringUtils.hasText(recordIdStr)) {
            request.setTargetRecordId(recordIdStr);
        }
    }

    private boolean isPluginHandledWithoutDslPersistence(CommandPipelineContext ctx) {
        if (ctx.isHasPluginHandler()) {
            return !ctx.isPluginRequiresDslPersistence();
        }
        if (extensionRegistry == null || ctx.getCommand() == null) {
            return false;
        }
        String handlerCode = resolvePluginHandlerCode(ctx.getCommand().getCode(), ctx.getExecConfig());
        return extensionRegistry.getCommandHandler(handlerCode)
                .map(handler -> {
                    boolean requiresPersistence = handler.requiresDslPersistence(
                            handlerCode, ctx.getExecConfig(), ctx.getRequest());
                    ctx.setHasPluginHandler(true);
                    ctx.setPluginRequiresDslPersistence(requiresPersistence);
                    return !requiresPersistence;
                })
                .orElse(false);
    }

    private String resolvePluginHandlerCode(String commandCode, Map<String, Object> execConfig) {
        if (execConfig != null) {
            Object handler = execConfig.get("handler");
            if (handler instanceof String handlerCode && StringUtils.hasText(handlerCode)) {
                return handlerCode.trim();
            }
        }
        return commandCode;
    }
}
