package com.auraboot.framework.bpm.dto;

import java.util.List;
import java.util.Map;

/**
 * DTO representing the node-level execution status of a process instance.
 * Used by the frontend to highlight active/completed nodes on the BPMN canvas.
 */
public record ProcessInstanceStatusDTO(
        String instanceId,
        String processDefinitionId,
        String status,
        List<NodeStatusDTO> currentNodes,
        List<NodeStatusDTO> completedNodes,
        Map<String, Object> variables
) {
}
