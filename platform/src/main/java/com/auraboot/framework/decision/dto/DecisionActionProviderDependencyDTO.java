package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DecisionActionProviderDependencyDTO {
    private String providerType;
    private List<String> providerCodes = new ArrayList<>();
    private String label;
    private Boolean required;
    private Boolean available;
    private String availabilityStatus;
    private String availabilityReason;
}
