package com.auraboot.framework.agentchat.spi;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentMemberDto {
    private Long agentId;
    private String agentCode;
    private String name;
    private String employeeTitle;
    private String avatarUrl;
    private String autoReplyMode;   // mention | always | off
    private String systemPrompt;
    private String soulProfile;     // JSON string
    private String tools;
}
