package com.auraboot.framework.agent.provider;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDiscoveryContext {
    private Long tenantId;
    private Long userId;
    private String agentCode;
    private String modelHint;      // model code hint from BIF
    private String intentHint;     // intent hint from BIF
    private String channel;        // request channel (web, cs_widget, im, ...); the discriminator an
                                   // always-on provider uses to decide whether its tools apply here
    @Builder.Default
    private int maxResults = 20;   // limit discovery results
}
