package com.auraboot.framework.eventpolicy.dto;

import com.auraboot.framework.eventpolicy.entity.DrtPolicyDefinitionEntity;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyVersionEntity;
import lombok.Data;

import java.time.Instant;

/**
 * Read model for the Event Policy governance console list.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class EventPolicyDefinitionSummary {

    private String pid;
    private String policyCode;
    private String policyName;
    private String eventType;
    private String targetType;
    private String targetKey;
    private Boolean enabled;
    private String owner;
    private Instant createdAt;
    private Instant updatedAt;

    private String latestVersionPid;
    private Integer version;
    private String status;
    private String phase;
    private String matchMode;
    private Instant publishedAt;

    public static EventPolicyDefinitionSummary of(
            DrtPolicyDefinitionEntity definition,
            DrtPolicyVersionEntity latestVersion) {
        EventPolicyDefinitionSummary summary = new EventPolicyDefinitionSummary();
        summary.setPid(definition.getPid());
        summary.setPolicyCode(definition.getPolicyCode());
        summary.setPolicyName(definition.getPolicyName());
        summary.setEventType(definition.getEventType());
        summary.setTargetType(definition.getTargetType());
        summary.setTargetKey(definition.getTargetKey());
        summary.setEnabled(definition.getEnabled());
        summary.setOwner(definition.getUpdatedBy() != null ? definition.getUpdatedBy() : definition.getCreatedBy());
        summary.setCreatedAt(definition.getCreatedAt());
        summary.setUpdatedAt(definition.getUpdatedAt());

        if (latestVersion != null) {
            summary.setLatestVersionPid(latestVersion.getPid());
            summary.setVersion(latestVersion.getVersion());
            summary.setStatus(latestVersion.getStatus());
            summary.setPhase(latestVersion.getPhase());
            summary.setMatchMode(latestVersion.getMatchMode());
            summary.setPublishedAt(latestVersion.getPublishedAt());
        }
        return summary;
    }
}
