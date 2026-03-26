package com.auraboot.framework.bpm.controller;

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

    @PostMapping("/cc")
    public ApiResponse<Void> sendCarbonCopy(@RequestBody Map<String, Object> request) {
        String taskId = (String) request.get("taskId");
        String processInstanceId = (String) request.get("processInstanceId");
        Long senderUserId = Long.valueOf(request.get("senderUserId").toString());
        @SuppressWarnings("unchecked")
        List<Long> recipientUserIds = ((List<Number>) request.get("recipientUserIds"))
                .stream().map(Number::longValue).toList();
        String content = (String) request.getOrDefault("content", "");

        notifyService.sendCarbonCopy(taskId, processInstanceId, senderUserId, recipientUserIds, content);
        return ApiResponse.ok();
    }

    @PostMapping("/urge")
    public ApiResponse<Void> sendUrge(@RequestBody Map<String, Object> request) {
        String taskId = (String) request.get("taskId");
        String processInstanceId = (String) request.get("processInstanceId");
        Long senderUserId = Long.valueOf(request.get("senderUserId").toString());
        Long assigneeUserId = Long.valueOf(request.get("assigneeUserId").toString());
        String content = (String) request.getOrDefault("content", "");

        notifyService.sendUrge(taskId, processInstanceId, senderUserId, assigneeUserId, content);
        return ApiResponse.ok();
    }

    @GetMapping("/received")
    public ApiResponse<List<BpmNotifyRecord>> getReceived(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "CC") String type) {
        return ApiResponse.ok(notifyService.getReceivedNotifications(userId, type));
    }

    @PutMapping("/{pid}/read")
    public ApiResponse<Void> markAsRead(@PathVariable String pid) {
        notifyService.markAsRead(pid);
        return ApiResponse.ok();
    }
}
