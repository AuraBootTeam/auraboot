package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.List;

/**
 * Read-only role x capability projection used by the DecisionOps permission governance tab.
 */
@Data
public class DecisionPermissionMatrixDTO {

    private List<DecisionPermissionRoleGrantDTO> roles;
}
