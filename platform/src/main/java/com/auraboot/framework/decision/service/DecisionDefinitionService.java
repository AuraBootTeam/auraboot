package com.auraboot.framework.decision.service;

import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtDefinitionDTO;
import com.auraboot.framework.common.dto.PageResult;

/**
 * Decision definition CRUD service (tenant-scoped).
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public interface DecisionDefinitionService {

    /** Create a new definition. Throws if the decision_code already exists in this tenant. */
    DrtDefinitionDTO create(DrtDefinitionCreateRequest request);

    /** Update name / description / scope / ownerModule / enabled for an existing definition. */
    DrtDefinitionDTO update(String pid, DrtDefinitionCreateRequest request);

    /** Find by PID (tenant-scoped). Returns null when not found. */
    DrtDefinitionDTO findByPid(String pid);

    /** Find by decision_code (tenant-scoped). Returns null when not found. */
    DrtDefinitionDTO findByCode(String decisionCode);

    /** Paginated list of definitions for the current tenant. */
    PageResult<DrtDefinitionDTO> list(String keyword, int page, int size);
}
