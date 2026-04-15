package com.auraboot.framework.engagement.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
public class UserEngagementDTO {
    private Long id;
    private String targetType;
    private String targetId;
    private String targetLabel;
    private Map<String, Object> targetContext;
    private String engagementType;
    private Integer sortOrder;
    private OffsetDateTime createdAt;

    @Data
    public static class ReorderRequest {
        private List<Long> orderedIds;
    }
}
