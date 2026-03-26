package com.auraboot.framework.governance.dto;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * Response DTO for governance policy details.
 */
@Data
public class PolicyResponse {

    private String pid;
    private String modelCode;
    private Boolean requireApproval;
    private Boolean autoSnapshot;
    private Long approvalChainId;
    private List<String> allowedEditors;
    private Date createdAt;
    private Date updatedAt;
}
