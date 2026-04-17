package com.auraboot.framework.saas.bootstrap.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class BootstrapStatusResponse {
    private boolean initialized;
    private boolean inProgress;
    private String mode;
    private List<String> missingParts;
    private String reason;
}
