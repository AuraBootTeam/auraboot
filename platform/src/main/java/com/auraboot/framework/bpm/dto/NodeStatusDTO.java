package com.auraboot.framework.bpm.dto;

/**
 * DTO representing the status of a single BPMN node within a process instance.
 */
public record NodeStatusDTO(
        String nodeId,
        String type,
        String name,
        String status,
        String assignee,
        String completedAt,
        String completedBy
) {
}
