package com.auraboot.framework.im.dto;

import lombok.Data;

@Data
public class ConversationAgentSettingsRequest {
    private Long conductorAgentId;
    private Integer aiContextWindow;
    private Boolean aiEnabled;
}
