package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.permission.service.UserPermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces command-level permissions declared in executionConfig.permissions, and records what it
 * decided on the context as a {@link CommandAuthorizationVerdict}.
 *
 * <p>The verdict changes no behaviour here — the same calls are allowed and denied as before. It
 * exists because "did not throw" is not the same statement as "authorized": a command declaring no
 * permissions leaves this phase without having granted anything, and only an explicit verdict lets
 * later stages tell those apart.</p>
 */
@Slf4j
@Component
@Order(200)
@RequiredArgsConstructor
public class CommandAuthorizationPhase implements CommandPhase {

    private final UserPermissionService userPermissionService;

    /**
     * Command codes already reported as undeclared. Bounded by the number of distinct commands, and
     * logging one line per execution would drown the signal it is meant to surface.
     */
    private final Set<String> reportedUndeclared = ConcurrentHashMap.newKeySet();

    @Override
    public String name() {
        return "authorization";
    }

    @Override
    public void execute(CommandPipelineContext ctx) {
        List<String> requiredPermissions = extractPermissions(ctx.getExecConfig().get("permissions"));
        if (requiredPermissions.isEmpty()) {
            reportUndeclared(ctx.getCommandCode());
            ctx.setAuthorizationVerdict(CommandAuthorizationVerdict
                    .notApplicable(CommandAuthorizationVerdict.REASON_NO_DECLARED_PERMISSIONS));
            return;
        }

        Long userId = ctx.getUserId();
        if (userId == null) {
            log.warn("Skipping command permission check because userId is absent: command={}",
                    ctx.getCommandCode());
            ctx.setAuthorizationVerdict(CommandAuthorizationVerdict
                    .notApplicable(CommandAuthorizationVerdict.REASON_NO_USER_CONTEXT));
            return;
        }

        for (String permission : requiredPermissions) {
            if (userPermissionService.hasPermission(userId, permission)) {
                ctx.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized(permission));
                return;
            }
        }

        throw new BusinessException(ResponseCode.FORBIDDEN,
                "Command permission denied: required one of " + String.join(", ", requiredPermissions));
    }

    /**
     * A command with a handler but no declared permissions passes this phase without any check
     * having happened. That is invisible today and is exactly the population that has to shrink
     * before downstream stages can inherit the boundary's authority — so say it out loud, once.
     */
    private void reportUndeclared(String commandCode) {
        if (commandCode != null && reportedUndeclared.add(commandCode)) {
            log.warn("Command declares no permissions, so no authorization decision was made: command={}",
                    commandCode);
        }
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
