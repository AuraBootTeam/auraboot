package com.auraboot.framework.automation.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Request to create a debug session
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class DebugSessionCreateRequest {
    /** Record ID to use as trigger context */
    private String recordId;

    /** Initial breakpoints (action indices) */
    private List<Integer> breakpoints;

    /** Custom trigger payload (overrides default) */
    private Map<String, Object> triggerPayload;
}
