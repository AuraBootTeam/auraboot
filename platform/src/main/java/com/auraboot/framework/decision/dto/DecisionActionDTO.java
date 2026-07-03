package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class DecisionActionDTO {
    private String actionType;
    private String label;
    private String category;
    private String description;
    private List<String> scopes = new ArrayList<>();
    private Boolean handlerAvailable;
    private Map<String, Object> inputSchema;
}
