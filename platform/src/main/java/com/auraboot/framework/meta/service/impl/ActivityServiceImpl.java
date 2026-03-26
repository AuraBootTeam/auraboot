package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.Activity;
import com.auraboot.framework.meta.mapper.ActivityMapper;
import com.auraboot.framework.meta.service.ActivityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService {

    private final ActivityMapper activityMapper;
    private final ObjectMapper objectMapper;

    @Override
    public Activity recordSystemActivity(Long tenantId, String objectModel, String objectRecord,
                                          String activityType, String subject,
                                          String commandCode, String operationType,
                                          Long actorId, String actorName,
                                          Map<String, Object> metadata) {
        Activity activity = new Activity();
        activity.setPid(UniqueIdGenerator.generate());
        activity.setTenantId(tenantId);
        activity.setObjectModel(objectModel);
        activity.setObjectRecord(objectRecord);
        activity.setActivityType(activityType);
        activity.setSubject(subject);
        activity.setActorType(actorId != null ? "user" : "system");
        activity.setActorId(actorId);
        activity.setActorName(actorName != null ? actorName : "System");
        activity.setCommandCode(commandCode);
        activity.setOperationType(operationType);
        activity.setOccurredAt(Instant.now());
        activity.setCreatedAt(Instant.now());

        if (metadata != null && !metadata.isEmpty()) {
            try {
                activity.setMetadata(objectMapper.writeValueAsString(metadata));
            } catch (Exception e) {
                log.warn("Failed to serialize activity metadata", e);
                activity.setMetadata("{}");
            }
        }

        activityMapper.insert(activity);
        log.debug("Recorded system activity: model={}, record={}, type={}, command={}",
                objectModel, objectRecord, activityType, commandCode);
        return activity;
    }

    @Override
    public Activity createUserActivity(Long tenantId, String objectModel, String objectRecord,
                                        String activityType, String subject, String content,
                                        Long actorId, String actorName) {
        Activity activity = new Activity();
        activity.setPid(UniqueIdGenerator.generate());
        activity.setTenantId(tenantId);
        activity.setObjectModel(objectModel);
        activity.setObjectRecord(objectRecord);
        activity.setActivityType(activityType);
        activity.setSubject(subject);
        activity.setContent(content);
        activity.setActorType("user");
        activity.setActorId(actorId);
        activity.setActorName(actorName);
        activity.setOccurredAt(Instant.now());
        activity.setCreatedAt(Instant.now());

        activityMapper.insert(activity);
        log.debug("Created user activity: model={}, record={}, type={}",
                objectModel, objectRecord, activityType);
        return activity;
    }

    @Override
    public List<Activity> getActivities(Long tenantId, String objectModel, String objectRecord, int limit) {
        return activityMapper.findByObjectRecord(tenantId, objectModel, objectRecord, limit > 0 ? limit : 50);
    }

    @Override
    public int countActivities(Long tenantId, String objectModel, String objectRecord) {
        return activityMapper.countByObjectRecord(tenantId, objectModel, objectRecord);
    }
}
