package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.service.impl.CommandCascadeDeleteExecutor;
import com.auraboot.framework.meta.service.impl.CommandFieldMapExecutor;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.RecordSnapshotReader;
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
        if (ctx.isHasPluginHandler() && !ctx.isPluginRequiresDslPersistence()) {
            fieldMapResults = new HashMap<>();
            log.info("Skipping FIELD_MAP for plugin-handled command: {}", ctx.getCommand().getCode());
        } else {
            List<BindingRule> fieldMapRules = ctx.getRulesByType().getOrDefault("field_map", Collections.emptyList());
            boolean noBindingRules = fieldMapRules.isEmpty();
            Map<String, Object> ec = ctx.getExecConfig();
            boolean hasInputFields = ec.containsKey("inputFields");
            boolean hasAutoSetFields = ec.containsKey("autoSetFields");
            boolean isDeleteOp = "delete".equalsIgnoreCase(ctx.getRequest().getOperationType());
            String cmdType = (String) ec.get("type");
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
}
