package com.auraboot.framework.agentchat.spi;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class ConfirmationPayload {
    private String operationType;       // create | update | delete | transition
    private String targetModel;
    private String description;
    private List<Map<String, Object>> dataPreview;
    private String toolCallId;
    private String sessionId;
}
