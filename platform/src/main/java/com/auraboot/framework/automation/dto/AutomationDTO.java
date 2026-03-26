package com.auraboot.framework.automation.dto;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.entity.TriggerConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Automation Data Transfer Object
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutomationDTO {

    private Long id;
    private String pid;
    private Long tenantId;

    private String name;
    private String description;
    private String modelCode;

    private String triggerType;
    private TriggerConfig triggerConfig;
    private String triggerCondition;

    private List<AutomationAction> actions;
    private Map<String, Object> flowConfig;

    private Boolean enabled;
    private Instant lastTriggeredAt;
    private Long triggerCount;

    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;

    // Display fields
    private String modelDisplayName;
}
