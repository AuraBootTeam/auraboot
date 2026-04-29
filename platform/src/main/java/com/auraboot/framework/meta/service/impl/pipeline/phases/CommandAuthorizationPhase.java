package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.service.UserPermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Enforces command-level permissions declared in executionConfig.permissions.
 */
@Slf4j
@Component
@Order(200)
@RequiredArgsConstructor
public class CommandAuthorizationPhase implements CommandPhase {

    private final UserPermissionService userPermissionService;

    @Override
    public String name() {
        return "authorization";
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        List<String> requiredPermissions = extractPermissions(ctx.getExecConfig().get("permissions"));
        if (requiredPermissions.isEmpty()) {
            return;
        }

        Long userId = ctx.getUserId();
        if (userId == null) {
            log.warn("Skipping command permission check because userId is absent: command={}",
                    ctx.getCommandCode());
            return;
        }

        for (String permission : requiredPermissions) {
            if (userPermissionService.hasPermission(userId, permission)) {
                return;
            }
        }

        throw new BusinessException(ResponseCode.FORBIDDEN,
                "Command permission denied: required one of " + String.join(", ", requiredPermissions));
    }

    private List<String> extractPermissions(Object rawPermissions) {
        if (!(rawPermissions instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }

        List<String> permissions = new ArrayList<>();
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                permissions.add(String.valueOf(value));
            }
        }
        return permissions;
    }
}
