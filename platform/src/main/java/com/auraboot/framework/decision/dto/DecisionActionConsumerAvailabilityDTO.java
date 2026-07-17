package com.auraboot.framework.decision.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DecisionActionConsumerAvailabilityDTO {
    private String consumerType;
    private Boolean handlerAvailable;
    private String availabilityStatus;
    private String availabilityReason;
    private List<DecisionActionProviderDependencyDTO> providerDependencies = new ArrayList<>();
}
