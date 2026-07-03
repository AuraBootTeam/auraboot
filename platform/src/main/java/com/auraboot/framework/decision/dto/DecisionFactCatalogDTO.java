package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DecisionFactCatalogDTO {
    private List<DecisionFactEntityDTO> entities = new ArrayList<>();
}
