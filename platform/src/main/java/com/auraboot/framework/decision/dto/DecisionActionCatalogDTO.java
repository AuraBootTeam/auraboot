package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DecisionActionCatalogDTO {
    private List<DecisionActionDTO> actions = new ArrayList<>();
}
