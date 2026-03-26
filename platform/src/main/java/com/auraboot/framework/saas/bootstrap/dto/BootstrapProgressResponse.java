package com.auraboot.framework.saas.bootstrap.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BootstrapProgressResponse {
    private String status;
    private String currentStep;
    private int totalSteps;
    private int completedSteps;
    private String error;
}
