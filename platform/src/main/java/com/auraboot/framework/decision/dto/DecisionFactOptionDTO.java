package com.auraboot.framework.decision.dto;

import lombok.Data;

@Data
public class DecisionFactOptionDTO {
    private String value;
    private String label;
    private String parentValue;
    private Boolean disabled;
}
