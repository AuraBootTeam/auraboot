package com.auraboot.framework.auth.dto;

import lombok.Data;

/**
 * Request body for initiating account deactivation.
 *
 * @since 7.1.0
 */
@Data
public class DeactivationRequest {

    /** Optional reason for deactivation */
    private String reason;

    /** JSON string of user consent — timestamp + agreement text */
    private String consentSnapshot;
}
