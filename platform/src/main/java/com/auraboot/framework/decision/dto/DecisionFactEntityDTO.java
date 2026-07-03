package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DecisionFactEntityDTO {
    private String scope;
    private String entityCode;
    private String modelCode;
    private String label;
    private String sourceType;
    private String sourceRef;
    private List<DecisionFactDTO> facts = new ArrayList<>();
}
