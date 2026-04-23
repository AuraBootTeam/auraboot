package com.auraboot.framework.bpm.handler;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.bpm.service.WithdrawService;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class WdCancelLeaveRequestHandler implements CommandHandlerExtension {

    public static final String COMMAND_CODE = "wd:cancel_leave_request";
    private static final String MODEL_CODE = "wd_leave_request";
    private static final String PROCESS_INSTANCE_FIELD = "wd_req_process_instance";
    private static final String DEFAULT_REASON = "Applicant cancelled leave request";

    private final SmartEngine smartEngine;
    private final WithdrawService withdrawService;

    @Override
    public String getCommandType() {
        return COMMAND_CODE;
    }

    @Override
    public Object execute(CommandContext context) {
        if (!MODEL_CODE.equals(context.modelCode())) {
            throw new BusinessException("Unsupported model for leave cancellation: " + context.modelCode());
        }

        if (context.dataAccessor() == null) {
            throw new BusinessException("Data accessor unavailable for leave cancellation");
        }

        Map<String, Object> record = context.dataAccessor().getById(MODEL_CODE, context.recordId());
        if (record == null || record.isEmpty()) {
            throw new BusinessException("Leave request not found: " + context.recordId());
        }

        Object processInstanceValue = record.get(PROCESS_INSTANCE_FIELD);
        String processInstanceId = processInstanceValue == null ? null : String.valueOf(processInstanceValue).trim();
        if (processInstanceId == null || processInstanceId.isEmpty()) {
            throw new BusinessException("Leave request is not in an active workflow");
        }

        String tenantId = String.valueOf(context.tenantId());
        List<TaskInstance> pendingTasks = smartEngine.getTaskQueryService()
                .findAllPendingTaskList(processInstanceId, tenantId);
        if (pendingTasks == null || pendingTasks.isEmpty()) {
            throw new BusinessException("No pending workflow task found for this leave request");
        }

        String reason = resolveReason(context.payload());
        withdrawService.withdraw(pendingTasks.get(0).getInstanceId(), reason);
        return Map.of("withdrawnProcessInstanceId", processInstanceId);
    }

    private String resolveReason(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return DEFAULT_REASON;
        }
        Object rawReason = payload.get("reason");
        if (rawReason == null || String.valueOf(rawReason).trim().isEmpty()) {
            rawReason = payload.get("cancelReason");
        }
        if (rawReason == null || String.valueOf(rawReason).trim().isEmpty()) {
            return DEFAULT_REASON;
        }
        return String.valueOf(rawReason).trim();
    }
}
