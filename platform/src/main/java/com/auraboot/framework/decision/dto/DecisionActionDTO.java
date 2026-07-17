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
    private String availabilityStatus;
    private String availabilityReason;
    private List<String> consumerTypes = new ArrayList<>();
    private List<DecisionActionConsumerAvailabilityDTO> consumerAvailability = new ArrayList<>();
    private List<DecisionActionProviderDependencyDTO> providerDependencies = new ArrayList<>();
    private Map<String, Object> inputSchema;
}
