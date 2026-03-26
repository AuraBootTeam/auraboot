package com.auraboot.framework.bpm.dto;

import lombok.Data;

import java.util.List;

/**
 * Request body for task reassignment.
 */
@Data
public class ReassignRequest {
    private List<Long> assigneeUserIds;
}
