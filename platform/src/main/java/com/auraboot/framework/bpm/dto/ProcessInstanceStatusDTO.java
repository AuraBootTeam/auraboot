package com.auraboot.framework.bpm.dto;

import java.util.List;
import java.util.Map;

/**
 * DTO representing the node-level execution status of a process instance.
 * Used by the frontend to highlight active/completed nodes on the BPMN canvas
 * and to drive client-side permission gating in {@code BpmPermissionService}
 * (initiator → withdraw, assignee → approve/reject/cc).
 *
 * @param startUserId ULID of the user who started the process instance, taken
 *                    directly from {@code ProcessInstance.getStartUserId()}.
 *                    Required for the frontend Layer 2 permission check; must
 *                    not be derived from process variables, because variables
 *                    only carry the value when the caller explicitly passes
 *                    {@code _startUserId} in the start payload.
 */
public record ProcessInstanceStatusDTO(
        String instanceId,
        String processDefinitionId,
        String startUserId,
        String status,
        List<NodeStatusDTO> currentNodes,
        List<NodeStatusDTO> completedNodes,
        Map<String, Object> variables
) {
}
