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

}
