package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.dto.AsyncTaskDTO;
import com.auraboot.framework.meta.dto.AsyncTaskSubmitRequest;
import com.auraboot.framework.meta.service.impl.AsyncTaskServiceImpl;
import com.auraboot.framework.meta.service.impl.CommandHandlerAsyncTaskExecutor;
import com.auraboot.framework.plugin.extension.AsyncTaskAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * Host-side bridge that lets a synchronous plugin command handler offload a
 * follow-up command onto the platform's background async-task queue. Mirrors the
 * way {@code HandlerPhase} dispatches an {@code handlerParams.async} command, so
 * the deferred command runs through the same {@link CommandHandlerAsyncTaskExecutor}
 * path with the originating tenant/user context preserved.
 */
public class AsyncTaskAccessorImpl implements AsyncTaskAccessor {

    private final AsyncTaskServiceImpl asyncTaskService;
    private final ObjectMapper objectMapper;
    private final Long tenantId;
    private final Long userId;

    public AsyncTaskAccessorImpl(AsyncTaskServiceImpl asyncTaskService, ObjectMapper objectMapper,
                                 Long tenantId, Long userId) {
        this.asyncTaskService = asyncTaskService;
        this.objectMapper = objectMapper;
        this.tenantId = tenantId;
        this.userId = userId;
    }

    @Override
    public String submitCommandTask(String commandCode, String modelCode, String recordId,
                                    Map<String, Object> payload) {
        if (asyncTaskService == null || commandCode == null || commandCode.isBlank()) {
            return null;
        }
        Map<String, Object> input = new HashMap<>();
        input.put("handlerCode", commandCode);
        input.put("commandCode", commandCode);
        input.put("tenantId", tenantId);
        input.put("userId", userId);
        input.put("modelCode", modelCode);
        input.put("recordId", recordId);
        input.put("payload", payload != null ? payload : Map.of());
        input.put("handlerParams", Map.of("async", true));

        AsyncTaskSubmitRequest request = new AsyncTaskSubmitRequest();
        request.setTaskType(CommandHandlerAsyncTaskExecutor.TASK_TYPE);
        request.setTaskName(commandCode);
        request.setInputParams(objectMapper.valueToTree(input));

        AsyncTaskDTO dto = asyncTaskService.submitTask(request, tenantId, userId);
        return dto != null ? dto.getTaskCode() : null;
    }
}
