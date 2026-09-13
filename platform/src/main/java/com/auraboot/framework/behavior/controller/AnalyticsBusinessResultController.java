package com.auraboot.framework.behavior.controller;

import com.auraboot.framework.behavior.service.AnalyticsBusinessResultService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analytics/suggestions")
@RequiredArgsConstructor
public class AnalyticsBusinessResultController {
    private final AnalyticsBusinessResultService results;

    @GetMapping("/{adoptionPid}/business-results")
    @RequirePermission("analytics.suggestion.read")
    public ApiResponse<AnalyticsBusinessResultService.Page> read(@PathVariable String adoptionPid,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.success(results.read(adoptionPid, page, pageSize));
    }
}
