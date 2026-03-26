package com.auraboot.framework.automation.dto;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.entity.TriggerConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Request DTO for creating an Automation
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Data
public class AutomationCreateRequest {

    @NotBlank(message = "Automation name is required")
    @Size(max = 200, message = "Name must be less than 200 characters")
    private String name;

    @Size(max = 1000, message = "Description must be less than 1000 characters")
    private String description;

    private String modelCode;

    private String triggerType;

    private TriggerConfig triggerConfig;

    @Size(max = 500, message = "Trigger condition must be less than 500 characters")
    private String triggerCondition;

    private List<AutomationAction> actions;

    private Map<String, Object> flowConfig;

    /**
     * Whether to enable immediately (default: false)
     */
    private Boolean enabled;
}
