package com.auraboot.framework.automation.controller;

import com.auraboot.framework.agent.dto.LlmChunk;
import com.auraboot.framework.automation.event.AutomationRunStreamPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live SSE feed for streaming LLM chunks emitted by automation
 * {@code llm_call} actions (E.1 Phase 1).
 *
 * <p>Endpoint:
 * {@code GET /api/admin/automation-runs/{runPid}/llm-stream?nodeId=...}
 *
 * <p><b>Authorisation.</b> Lives under {@code /api/admin/**} so the platform
 * {@link com.auraboot.framework.application.security.AdminRoleInterceptor}
 * already enforces the tenant-admin role before any handler executes — same
 * gate as the read-only agent-run replay surface (per spec Q10).
 *
 * <p><b>Disconnect handling.</b> Per spec Q4 there is no replay buffer: a
 * dropped client reconnects and starts subscribing from chunk N+1 (where N
 * was the last delivered chunk on the prior socket). They do NOT see chunks
 * that fired during the disconnected window. The accumulated full output is
 * still authoritatively available on the parent run record after the
 * automation completes.
 *
 * <p><b>Drop accounting.</b> The terminal {@code done} envelope carries the
 * cumulative drop counter for this (runPid, nodeId) pair so the admin UI
 * can render a red "X chunks dropped" badge — this is the user-visible
 * surface of {@code aura_workflow_stream_chunk_dropped_total}.
 *
 * <p>Persistence: chunks are NOT persisted (spec Q11). Live-only.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/automation-runs")
public class AutomationRunStreamController {

    /** Long-poll keepalive: 30 minutes is well above any realistic LLM call. */
    private static final long SSE_TIMEOUT_MS = 30L * 60L * 1000L;

    private final AutomationRunStreamPublisher streamPublisher;
    private final ObjectMapper objectMapper;

    @Autowired
    public AutomationRunStreamController(AutomationRunStreamPublisher streamPublisher,
                                         ObjectMapper objectMapper) {
        this.streamPublisher = streamPublisher;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/{runPid}/llm-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLlmChunks(@PathVariable("runPid") String runPid,
                                      @RequestParam("nodeId") String nodeId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // Subscribe BEFORE returning so the first chunk after subscription is
        // never lost to a registration race. Spec Q4: no buffer / no replay,
        // so the registration window matters.
        AutomationRunStreamPublisher.Subscription sub =
                streamPublisher.subscribe(runPid, nodeId, (chunk, seq) -> {
                    try {
                        sendChunkEvent(emitter, chunk, seq);
                        if (chunk.done()) {
                            sendDoneEvent(emitter, runPid, nodeId);
                            emitter.complete();
                        }
                    } catch (IOException e) {
                        // Client disconnected mid-stream — close the emitter
                        // and let the outer hooks unsubscribe.
                        emitter.completeWithError(e);
                    }
                });

        Runnable cleanup = sub::unsubscribe;
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());

        return emitter;
    }

    private void sendChunkEvent(SseEmitter emitter, LlmChunk chunk, long seq) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("seq", seq);
        payload.put("delta", chunk.delta() == null ? "" : chunk.delta());
        if (chunk.thinkingDelta() != null) {
            payload.put("thinkingDelta", chunk.thinkingDelta());
        }
        payload.put("done", chunk.done());
        try {
            emitter.send(SseEmitter.event()
                    .name("chunk")
                    .data(objectMapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Should never happen: payload is a plain Map of strings/numbers.
            // Wrap as IOException so the existing emitter cleanup path runs.
            throw new IOException("failed to serialise LLM chunk payload", e);
        }
    }

    private void sendDoneEvent(SseEmitter emitter, String runPid, String nodeId) throws IOException {
        long dropped = streamPublisher.getDroppedCount(runPid, nodeId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("droppedCount", dropped);
        try {
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(objectMapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException("failed to serialise LLM done payload", e);
        }
    }
}
