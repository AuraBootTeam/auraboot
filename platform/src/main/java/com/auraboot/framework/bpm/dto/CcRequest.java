package com.auraboot.framework.bpm.dto;

import lombok.Data;

import java.util.List;

/**
 * Request body for carbon copy (CC) on an approval task.
 * Creates informational notifications for each recipient without modifying the task itself.
 */
@Data
public class CcRequest {
    /** User IDs to carbon copy. Required, must be non-empty. */
    private List<Long> ccUserIds;
    /** Optional message/comment to include with the CC notification. */
    private String comment;
}
