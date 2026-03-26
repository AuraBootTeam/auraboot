package com.auraboot.framework.governance.dto;

import lombok.Data;

import java.util.List;

/**
 * DTO for creating or updating a governance policy.
 */
@Data
public class PolicyCreateDTO {

    /** The model code this policy applies to */
    private String modelCode;

    /** If true, edits on this model require change request approval */
    private Boolean requireApproval;

    /** If true, every edit on this model creates a version snapshot */
    private Boolean autoSnapshot;

    /** Optional linked approval chain ID */
    private Long approvalChainId;

    /** List of role codes that can propose changes */
    private List<String> allowedEditors;
}
