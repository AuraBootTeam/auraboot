package com.auraboot.framework.decision.service;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.decision.dto.ConditionFragmentCreateRequest;
import com.auraboot.framework.decision.dto.ConditionFragmentDTO;
import com.auraboot.framework.decision.dto.ConditionFragmentEvaluateRequest;
import com.auraboot.framework.decision.dto.ConditionFragmentEvaluationDTO;
import com.auraboot.framework.decision.dto.ConditionFragmentImpactDTO;
import com.auraboot.framework.decision.dto.ConditionFragmentVersionCreateRequest;

import java.util.List;

/**
 * Service for reusable condition fragments.
 */
public interface ConditionFragmentService {
    ConditionFragmentDTO create(ConditionFragmentCreateRequest request);
    ConditionFragmentDTO createVersion(String fragmentCode, ConditionFragmentVersionCreateRequest request);
    ConditionFragmentDTO findByCode(String fragmentCode);
    List<ConditionFragmentDTO> listVersions(String fragmentCode);
    ConditionFragmentDTO validate(String pid);
    ConditionFragmentDTO publish(String pid, boolean impactAcknowledged);
    PageResult<ConditionFragmentDTO> list(String keyword, String scopeType, String scopeRef, int page, int size);
    ConditionFragmentEvaluationDTO evaluate(String fragmentCode, ConditionFragmentEvaluateRequest request);
    ConditionFragmentImpactDTO impact(String fragmentCode);
}
