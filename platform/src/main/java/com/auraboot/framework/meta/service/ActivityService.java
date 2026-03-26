package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.entity.Activity;

import java.util.List;
import java.util.Map;

/**
 * Service for unified activity timeline.
 * Handles both system-generated and user-created activities.
 */
public interface ActivityService {

    /**
     * Record a system-generated activity (from command execution).
     */
    Activity recordSystemActivity(Long tenantId, String objectModel, String objectRecord,
                                   String activityType, String subject,
                                   String commandCode, String operationType,
                                   Long actorId, String actorName,
                                   Map<String, Object> metadata);

    /**
     * Create a user activity (NOTE, CALL, EMAIL, MEETING, etc.)
     */
    Activity createUserActivity(Long tenantId, String objectModel, String objectRecord,
                                 String activityType, String subject, String content,
                                 Long actorId, String actorName);

    /**
     * Get activities for a record, most recent first.
     */
    List<Activity> getActivities(Long tenantId, String objectModel, String objectRecord, int limit);

    /**
     * Count activities for a record.
     */
    int countActivities(Long tenantId, String objectModel, String objectRecord);
}
