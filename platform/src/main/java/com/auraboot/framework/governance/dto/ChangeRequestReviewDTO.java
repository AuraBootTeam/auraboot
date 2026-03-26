package com.auraboot.framework.governance.dto;

import lombok.Data;

/**
 * DTO for reviewing (approving/rejecting) a change request.
 */
@Data
public class ChangeRequestReviewDTO {

    /** APPROVED or REJECTED */
    private String action;

    /** Reviewer's comment */
    private String comment;
}
