package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class DecisionFactDTO {
    private String factKey;
    private String scope;
    private String path;
    private String label;
    private String dataType;
    private String modelCode;
    private String sourceType;
    private List<String> operators = new ArrayList<>();
    private String dictCode;
    private List<DecisionFactOptionDTO> allowedValues = new ArrayList<>();
    private Map<String, Object> reference;
    private Boolean required;
    private Boolean visible;
    private Boolean editable;
    private String permission;
}
