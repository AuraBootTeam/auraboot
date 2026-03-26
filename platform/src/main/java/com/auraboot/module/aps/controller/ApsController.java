package com.auraboot.module.aps.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.module.aps.dto.GanttData;
import com.auraboot.module.aps.dto.ScheduleRequest;
import com.auraboot.module.aps.dto.ScheduleResult;
import com.auraboot.module.aps.engine.SchedulingEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/aps")
@RequiredArgsConstructor
public class ApsController {

    private final SchedulingEngine engine;

    @PostMapping("/schedule")
    public ApiResponse<ScheduleResult> schedule(@RequestBody ScheduleRequest request) {
        ApiResponse<Void> validation = validateRequest(request);
        if (validation != null) return ApiResponse.error(validation.getMessage());
        ScheduleResult result = engine.schedule(request, "forwardFifo");
        return ApiResponse.success(result);
    }

    @PostMapping("/schedule/{strategy}")
    public ApiResponse<ScheduleResult> scheduleWithStrategy(
            @PathVariable String strategy,
            @RequestBody ScheduleRequest request) {
        ApiResponse<Void> validation = validateRequest(request);
        if (validation != null) return ApiResponse.error(validation.getMessage());
        ScheduleResult result = engine.schedule(request, strategy);
        return ApiResponse.success(result);
    }

    @PostMapping("/gantt/{strategy}")
    public ApiResponse<GanttData> gantt(
            @PathVariable String strategy,
            @RequestBody ScheduleRequest request) {
        ApiResponse<Void> validation = validateRequest(request);
        if (validation != null) return ApiResponse.error(validation.getMessage());
        GanttData gantt = engine.scheduleWithGantt(request, strategy);
        return ApiResponse.success(gantt);
    }

    @GetMapping("/strategies")
    public ApiResponse<List<String>> strategies() {
        return ApiResponse.success(engine.getAvailableStrategies());
    }

    private ApiResponse<Void> validateRequest(ScheduleRequest request) {
        if (request.getJobs() == null || request.getJobs().isEmpty()) {
            return ApiResponse.error("Jobs list cannot be empty");
        }
        if (request.getResources() == null || request.getResources().isEmpty()) {
            return ApiResponse.error("Resources list cannot be empty");
        }
        return null;
    }
}
