package com.auraboot.framework.agentchat.sse;

import com.auraboot.framework.application.tenant.MetaContext;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    public SseEmitter stream(@RequestParam(required = false) String lastEventId) {
        Long userId = MetaContext.getCurrentUserId();
        // lastEventId is accepted for SSE reconnect protocol compliance.
        // Catch-up for missed messages is handled client-side via the incremental
        // sync API: GET /conversations/{id}/messages?afterSeq=N
        return sseEmitterManager.createEmitter(userId);
    }
}
