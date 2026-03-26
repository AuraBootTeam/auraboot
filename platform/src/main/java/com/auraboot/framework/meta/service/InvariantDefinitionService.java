package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.InvariantDefinitionCreateRequest;
import com.auraboot.framework.meta.entity.InvariantDefinition;

import java.util.List;

/**
 * Invariant Definition Service interface.
 * Manages CRUD and publish lifecycle of invariant definitions.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
public interface InvariantDefinitionService {

    InvariantDefinition create(InvariantDefinitionCreateRequest request);

    InvariantDefinition getByPid(String pid);

    InvariantDefinition getCurrentByCode(String code);

    List<InvariantDefinition> listByModelCode(String modelCode);

    InvariantDefinition update(String pid, InvariantDefinitionCreateRequest request);

    void publish(String pid);

    void delete(String pid);
}
