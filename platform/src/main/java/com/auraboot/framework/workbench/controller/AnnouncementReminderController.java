package com.auraboot.framework.workbench.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.workbench.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Read-only qualification with an owner-bound, expiring server preview; acceptance uses unified commands. */
@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
@RequirePermission("announcement.announcement.remind")
public class AnnouncementReminderController {
    private final AnnouncementReminderService reminders;
    @PostMapping("/records/{recordPid}/versions/{versionPid}/reminders/preview")
    public ApiResponse<Map<String,Object>> preview(@PathVariable String recordPid,@PathVariable String versionPid,@RequestBody AnnouncementStatistics.Filters filters) {
        return ApiResponse.success(reminders.preview(recordPid,versionPid,filters));
    }
    @GetMapping("/reminders/previews/{previewPid}")
    public ApiResponse<Map<String,Object>> previewStatus(@PathVariable String previewPid) {return ApiResponse.success(reminders.previewStatus(previewPid));}
    @GetMapping("/reminders/batches/{batchPid}")
    public ApiResponse<Map<String,Object>> batchStatus(@PathVariable String batchPid) {return ApiResponse.success(reminders.batchStatus(batchPid));}
    @GetMapping("/records/{recordPid}/versions/{versionPid}/reminders/plan")
    public ApiResponse<Map<String,Object>> planStatus(@PathVariable String recordPid,@PathVariable String versionPid) {return ApiResponse.success(reminders.planStatus(recordPid,versionPid));}

    @GetMapping("/versions/{versionPid}/reminders/plan")
    public ApiResponse<Map<String,Object>> planByVersion(@PathVariable String versionPid) {return ApiResponse.success(reminders.planStatusByVersion(versionPid));}

    @GetMapping("/records/{recordPid}/reminders/plan")
    public ApiResponse<Map<String,Object>> latestPlan(@PathVariable String recordPid) {return ApiResponse.success(reminders.latestPlanStatus(recordPid));}
    @GetMapping("/versions/{versionPid}/reminders/batches")
    public ApiResponse<Map<String,Object>> batches(@PathVariable String versionPid,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {
        return ApiResponse.success(reminders.batchHistory(versionPid,pageNum,pageSize));
    }

}
