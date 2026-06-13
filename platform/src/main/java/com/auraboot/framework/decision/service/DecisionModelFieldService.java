package com.auraboot.framework.decision.service;

import com.auraboot.framework.decision.dto.DecisionModelFieldDTO;

import java.util.List;

/**
 * Builds the DecisionOps data-model field catalogue from persisted decision metadata.
 */
public interface DecisionModelFieldService {

    List<DecisionModelFieldDTO> listFields();
}
