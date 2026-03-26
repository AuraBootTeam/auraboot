package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class CommandExecuteResult {
    private String commandCode;
    private String phaseReached;
    private Map<String, Object> data;
    private long executionTimeMs;
    private boolean idempotentReplay;
}
