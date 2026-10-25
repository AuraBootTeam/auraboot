package com.auraboot.framework.aurabot.dto;

import lombok.Data;

@Data
public class AuraBotMessageCreateRequest {
    private String content;
    private String clientMsgId;
    private String traceId;
    private Boolean error;
}
