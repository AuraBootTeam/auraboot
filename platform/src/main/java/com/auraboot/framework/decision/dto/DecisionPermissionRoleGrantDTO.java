package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.Map;

/**
 * Role grant row in the DecisionOps permission matrix.
 */
@Data
public class DecisionPermissionRoleGrantDTO {

    private String role;
    private String roleCode;
    private String rolePid;
    private Map<String, Boolean> caps;
    private Map<String, DecisionPermissionCapabilityDTO> capabilities;
}
