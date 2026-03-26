package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.DecisionDefinitionCreateRequest;
import com.auraboot.framework.meta.entity.DecisionDefinition;

import java.util.List;

/**
 * Decision Definition Service.
 * CRUD and publish for decision definitions.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
public interface DecisionDefinitionService {

    DecisionDefinition create(DecisionDefinitionCreateRequest request);

    DecisionDefinition getByPid(String pid);

    DecisionDefinition getCurrentByCode(String code);

    List<DecisionDefinition> listBySubjectType(String subjectType);

    DecisionDefinition update(String pid, DecisionDefinitionCreateRequest request);

    void publish(String pid);

    void delete(String pid);
}
