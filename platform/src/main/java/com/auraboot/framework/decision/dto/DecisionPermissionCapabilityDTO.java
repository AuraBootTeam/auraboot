package com.auraboot.framework.decision.dto;

import lombok.Data;

/**
 * Permission metadata for one DecisionOps governance capability.
 */
@Data
public class DecisionPermissionCapabilityDTO {

    private String permissionCode;
    private boolean granted;
}
