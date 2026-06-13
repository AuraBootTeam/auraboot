package com.auraboot.framework.decision.service;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.decision.dto.DecisionRolloutActionRequest;
import com.auraboot.framework.decision.dto.DecisionRolloutCreateRequest;
import com.auraboot.framework.decision.dto.DecisionRolloutDTO;
import com.auraboot.framework.decision.dto.DecisionRolloutMetricsDTO;
import com.auraboot.framework.decision.dto.DrtEvaluateRequest;
import com.auraboot.framework.decision.entity.DrtVersionEntity;
import com.auraboot.framework.decision.model.DecisionRolloutSelection;

import java.util.List;

public interface DecisionRolloutService {

    DecisionRolloutDTO create(String decisionCode, DecisionRolloutCreateRequest request);

    List<DecisionRolloutDTO> list(String decisionCode);

    PageResult<DecisionRolloutDTO> listPage(
            String decisionCode,
            String status,
            String keyword,
            int page,
            int size,
            String sortField,
            String sortOrder);

    DecisionRolloutDTO get(String pid);

    DecisionRolloutDTO active(String decisionCode);

    DecisionRolloutDTO activate(String pid, DecisionRolloutActionRequest request);

    DecisionRolloutDTO pause(String pid, DecisionRolloutActionRequest request);

    DecisionRolloutDTO promote(String pid, DecisionRolloutActionRequest request);

    DecisionRolloutDTO rollback(String pid, DecisionRolloutActionRequest request);

    DecisionRolloutMetricsDTO metrics(String pid);

    DecisionRolloutMetricsDTO metrics(String pid, int windowHours, int bucketMinutes, boolean refresh);

    DecisionRolloutSelection select(Long tenantId, DrtEvaluateRequest request, List<DrtVersionEntity> candidates);
}
