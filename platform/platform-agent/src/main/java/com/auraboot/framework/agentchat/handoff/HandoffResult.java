package com.auraboot.framework.agentchat.handoff;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HandoffResult {
    private boolean success;
    private Long targetAgentId;
    private String targetAgentCode;
    private String context;
    private String error;
}
