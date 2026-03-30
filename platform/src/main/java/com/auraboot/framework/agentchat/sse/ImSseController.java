package com.auraboot.framework.agentchat.sse;

import com.auraboot.framework.application.tenant.MetaContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/im")
public class ImSseController {

    private final SseEmitterManager sseEmitterManager;

    public ImSseController(SseEmitterManager sseEmitterManager) {
        this.sseEmitterManager = sseEmitterManager;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        Long userId = MetaContext.getCurrentUserId();
        return sseEmitterManager.createEmitter(userId);
    }
}
