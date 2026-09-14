package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.ChangeLogQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.entity.DataChangeLog;
import com.auraboot.framework.meta.service.ChangeLogService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * REST controller for querying data change history.
 *
 * @since 5.1.0
 */
@RestController
@RequestMapping("/api/meta/change-logs")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.META_CHANGELOG_READ)
public class ChangeLogController {

    private final ChangeLogService changeLogService;
    private final UserService userService;

    /**
     * Get change history for a specific record.
     * GET /api/meta/change-logs/history?modelCode=xxx&recordPid=xxx
     */
    @GetMapping("/history")
    public ApiResponse<List<DataChangeLog>> getHistory(
            @RequestParam String modelCode,
            @RequestParam String recordPid) {
        List<DataChangeLog> history = changeLogService.getHistory(modelCode, recordPid);
        resolveActorNames(history);
        return ApiResponse.success(history);
    }

    /**
     * Populate {@code changedByName} so clients render a readable actor instead
     * of the raw user id. Resolution is best-effort: deleted or foreign users
     * simply leave the name null.
     */
    private void resolveActorNames(List<DataChangeLog> history) {
        if (history == null || history.isEmpty()) {
            return;
        }
        Set<Long> userIds = history.stream()
                .map(DataChangeLog::getChangedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, String> names = userService.findByUserIds(userIds).stream()
                .collect(Collectors.toMap(
                        User::getId,
                        ChangeLogController::displayNameOf,
                        (a, b) -> a));
        for (DataChangeLog log : history) {
            if (log.getChangedBy() != null) {
                log.setChangedByName(names.get(log.getChangedBy()));
            }
        }
    }

    private static String displayNameOf(User user) {
        if (user.getNickName() != null && !user.getNickName().isBlank()) {
            return user.getNickName();
        }
        if (user.getUserName() != null && !user.getUserName().isBlank()) {
            return user.getUserName();
        }
        return user.getEmail();
    }

    /**
     * Get change logs for current user with pagination.
     * GET /api/meta/change-logs/my
     */
    @GetMapping("/my")
    public ApiResponse<PaginationResult<DataChangeLog>> getMyChanges(ChangeLogQueryRequest request) {
        Long userId = MetaContext.getCurrentUserId();
        PaginationResult<DataChangeLog> result = changeLogService.getByUser(userId, request);
        return ApiResponse.success(result);
    }

    /**
     * Get a single change log entry.
     * GET /api/meta/change-logs/{id}
     */
    @GetMapping("/{id}")
    public ApiResponse<DataChangeLog> getById(@PathVariable Long id) {
        DataChangeLog log = changeLogService.getById(id);
        if (log == null) {
            return ApiResponse.error("Change log not found: " + id);
        }
        return ApiResponse.success(log);
    }
}
