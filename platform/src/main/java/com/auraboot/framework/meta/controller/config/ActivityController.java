package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.entity.Activity;
import com.auraboot.framework.meta.service.ActivityService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for unified activity timeline.
 * Provides read access to activity records for any model.
 */
@RestController
@RequestMapping("/api/activities")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;

    /**
     * Get activities for a specific record.
     * GET /api/activities?objectModel=sl_sales_order&objectRecord=01XXXX&limit=50
     */
    @GetMapping
    public ApiResponse<List<Activity>> getActivities(
            @RequestParam String objectModel,
            @RequestParam String objectRecord,
            @RequestParam(defaultValue = "50") int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Activity> activities = activityService.getActivities(tenantId, objectModel, objectRecord, limit);
        return ApiResponse.success(activities);
    }

    /**
     * Create a user activity (NOTE, CALL, EMAIL, MEETING, etc.)
     * POST /api/activities
     */
    @PostMapping
    public ApiResponse<Activity> createActivity(@RequestBody CreateActivityRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long actorId = MetaContext.getCurrentUserId();
        String actorName = MetaContext.getCurrentUsername();

        Activity activity = activityService.createUserActivity(
                tenantId, request.getObjectModel(), request.getObjectRecord(),
                request.getActivityType(), request.getSubject(), request.getContent(),
                actorId, actorName);
        return ApiResponse.success(activity);
    }

    /**
     * Count activities for a record.
     * GET /api/activities/count?objectModel=sl_sales_order&objectRecord=01XXXX
     */
    @GetMapping("/count")
    public ApiResponse<Integer> countActivities(
            @RequestParam String objectModel,
            @RequestParam String objectRecord) {
        Long tenantId = MetaContext.getCurrentTenantId();
        int count = activityService.countActivities(tenantId, objectModel, objectRecord);
        return ApiResponse.success(count);
    }

    @Data
    public static class CreateActivityRequest {
        private String objectModel;
        private String objectRecord;
        private String activityType;
        private String subject;
        private String content;
    }
}
