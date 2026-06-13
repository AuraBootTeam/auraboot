package com.auraboot.framework.decision.service;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.decision.dto.DecisionModelFieldDTO;
import com.auraboot.framework.decision.dto.DecisionRolloutPolicyDTO;

/**
 * Read-only DecisionOps governance projections.
 */
public interface DecisionGovernanceQueryService {

    PageResult<DecisionRolloutPolicyDTO> listRollouts(String decisionCode, int page, int size);

    PageResult<DecisionModelFieldDTO> listModelFields(String decisionCode, int page, int size);
}
