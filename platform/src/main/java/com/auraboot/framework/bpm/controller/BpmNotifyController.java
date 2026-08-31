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
    private final com.auraboot.framework.bpm.service.CcService ccService;

    @PostMapping("/cc")
    public ApiResponse<Void> sendCarbonCopy(@RequestBody Map<String, Object> request) {
        String taskId = (String) request.get("taskId");
        // Recipients arrive as ab_user pid strings (the MemberPicker identity).
        // The legacy `Number(pid)`-on-the-frontend contract produced NaN → JSON
        // null → NPE here; resolve pids server-side and fail fast on unknown ones.
        Object rawRecipients = request.get("recipientUserIds");
        if (!(rawRecipients instanceof List<?> rawList) || rawList.isEmpty()) {
            throw new IllegalArgumentException("recipientUserIds must be a non-empty list of user pids");
        }
        String content = (String) request.getOrDefault("content", "");

        // UI CC goes through the same policy-guarded command service as task
        // API and automation CC. Resolve the process instance from the task;
        // a body-supplied instance ID is never trusted.
        ccService.cc(taskId, rawList.stream().map(String::valueOf).toList(), content, "UI");
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
