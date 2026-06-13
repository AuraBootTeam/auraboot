package com.auraboot.framework.decision.dto;

import lombok.Data;

/**
 * Optional request body for risky version state transitions.
 */
@Data
public class DecisionVersionTransitionRequest {

    /**
     * The caller has reviewed the current blast-radius impact summary.
     */
    private Boolean impactAcknowledged;

    /**
     * Optional approval/rejection note.
     */
    private String note;
}
