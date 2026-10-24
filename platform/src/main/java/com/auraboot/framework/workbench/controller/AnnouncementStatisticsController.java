package com.auraboot.framework.workbench.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.workbench.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Owner- and membership-scoped fixed-time statistics; private authorization facts never cross this boundary. */
@RestController
@RequestMapping("/api/announcements")
@RequiredArgsConstructor
@RequirePermission("announcement.announcement.stats")
public class AnnouncementStatisticsController {
    private final AnnouncementStatisticsService statistics;
    @PostMapping("/records/{recordPid}/statistics/snapshots")
    public ApiResponse<Map<String,Object>> captureRecord(@PathVariable String recordPid,@RequestBody AnnouncementStatistics.Filters filters) {
        return ApiResponse.success(statistics.captureRecord(recordPid,filters));
    }
    @GetMapping("/records/{recordPid}/statistics/versions")
    public ApiResponse<java.util.List<AnnouncementAudienceService.Option>> versions(@PathVariable String recordPid) {
        return ApiResponse.success(statistics.versions(recordPid));
    }
    @PostMapping("/versions/{versionPid}/statistics/snapshots")
    public ApiResponse<Map<String,Object>> capture(@PathVariable String versionPid,@RequestBody AnnouncementStatistics.Filters filters) {
        return ApiResponse.success(statistics.capture(versionPid,filters));
    }
    @GetMapping("/statistics/snapshots/{snapshotPid}")
    public ApiResponse<Map<String,Object>> summary(@PathVariable String snapshotPid) {return ApiResponse.success(statistics.summary(snapshotPid));}
    @GetMapping("/statistics/snapshots/{snapshotPid}/departments")
    public ApiResponse<java.util.List<AnnouncementAudienceService.Option>> departments(@PathVariable String snapshotPid) {
        return ApiResponse.success(statistics.departments(snapshotPid));
    }
    @GetMapping("/statistics/snapshots/{snapshotPid}/recipients")
    public ApiResponse<Map<String,Object>> page(@PathVariable String snapshotPid,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize) {
        return ApiResponse.success(statistics.page(snapshotPid,pageNum,pageSize));
    }
}
