package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmNotifyRecord;
import com.auraboot.framework.bpm.service.BpmNotifyService;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bpm/notify")
@RequiredArgsConstructor
public class BpmNotifyController {

    private final BpmNotifyService notifyService;
    private final com.auraboot.framework.user.service.UserService userService;

    @PostMapping("/cc")
    public ApiResponse<Void> sendCarbonCopy(@RequestBody Map<String, Object> request) {
        String taskId = (String) request.get("taskId");
        String processInstanceId = (String) request.get("processInstanceId");
        // Sender is the authenticated caller — never trust a body-supplied senderUserId
        // (that let any user impersonate another when sending CC notifications).
        Long senderUserId = MetaContext.getCurrentUserId();
        // Recipients arrive as ab_user pid strings (the MemberPicker identity).
        // The legacy `Number(pid)`-on-the-frontend contract produced NaN → JSON
        // null → NPE here; resolve pids server-side and fail fast on unknown ones.
        Object rawRecipients = request.get("recipientUserIds");
        if (!(rawRecipients instanceof List<?> rawList) || rawList.isEmpty()) {
            throw new IllegalArgumentException("recipientUserIds must be a non-empty list of user pids");
        }
        List<Long> recipientUserIds = rawList.stream()
                .map(String::valueOf)
                .map(pid -> {
                    com.auraboot.framework.user.dao.entity.User user = userService.findByPid(pid);
                    if (user == null || user.getId() == null) {
                        throw new IllegalArgumentException("Unknown recipient user pid: " + pid);
                    }
                    return user.getId();
                })
                .toList();
        String content = (String) request.getOrDefault("content", "");

        notifyService.sendCarbonCopy(taskId, processInstanceId, senderUserId, recipientUserIds, content);
        return ApiResponse.ok();
    }

    @PostMapping("/urge")
    public ApiResponse<Void> sendUrge(@RequestBody Map<String, Object> request) {
        String taskId = (String) request.get("taskId");
        String processInstanceId = (String) request.get("processInstanceId");
        // Sender is the authenticated caller — never trust a body-supplied senderUserId.
        Long senderUserId = MetaContext.getCurrentUserId();
        Long assigneeUserId = Long.valueOf(request.get("assigneeUserId").toString());
        String content = (String) request.getOrDefault("content", "");

        notifyService.sendUrge(taskId, processInstanceId, senderUserId, assigneeUserId, content);
        return ApiResponse.ok();
    }

    @GetMapping("/received")
    public ApiResponse<List<BpmNotifyRecord>> getReceived(
            @RequestParam(defaultValue = "CC") String type) {
        // Always scope to the authenticated caller — a body/query-supplied userId let any user
        // read another user's received notifications (IDOR).
        Long userId = MetaContext.getCurrentUserId();
        return ApiResponse.ok(notifyService.getReceivedNotifications(userId, type));
    }

    @PutMapping("/{pid}/read")
    public ApiResponse<Void> markAsRead(@PathVariable String pid) {
        notifyService.markAsRead(pid);
        return ApiResponse.ok();
    }
}
