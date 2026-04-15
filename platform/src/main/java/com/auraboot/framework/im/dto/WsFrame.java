package com.auraboot.framework.im.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WsFrame {

    private String type;      // SEND, SYNC, READ, TYPING, PING (client→server)
                               // MESSAGE, SYNC_RESULT, SEND_ACK, READ_RECEIPT, TYPING_INDICATOR, PONG, ERROR (server→client)
    private String requestId; // client-generated correlation id
    private Object data;      // payload, varies by type
}
