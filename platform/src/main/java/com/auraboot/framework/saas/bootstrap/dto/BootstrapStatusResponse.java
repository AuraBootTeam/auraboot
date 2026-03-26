package com.auraboot.framework.saas.bootstrap.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BootstrapStatusResponse {
    private boolean initialized;
    private boolean inProgress;
    private String mode;
}
