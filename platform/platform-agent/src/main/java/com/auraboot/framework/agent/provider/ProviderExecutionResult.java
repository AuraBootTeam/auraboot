package com.auraboot.framework.agent.provider;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderExecutionResult {
    private boolean success;
    private Map<String, Object> data;
    private String errorMessage;
    private long durationMs;
}
