package com.auraboot.framework.decision.service;

import com.auraboot.framework.decision.dto.DecisionPermissionMatrixDTO;

/**
 * Builds the DecisionOps read-only permission governance projection.
 */
public interface DecisionPermissionMatrixService {

    DecisionPermissionMatrixDTO getMatrix();
}
