package com.auraboot.framework.test.controller;

import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.websocket.ImWebSocketHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test-only bridge endpoint that fires IM member-event WebSocket frames on demand.
 * <p>
 * Activated only when the "test" Spring profile is enabled — never exposed in production.
 * Allows iOS XCUITest and Playwright E2E setups to trigger member events (member_added,
 * member_removed, self_kicked, member_left, conversation_renamed, conversation_dissolved)
 * without having to drive the full HTTP → DB → WS path through the real conversation
 * endpoints. Routes through {@link ImWebSocketHandler#broadcastEvent} — the same path
 * real member operations use — so client-side event handling is exercised faithfully.
 *
 * <h3>API contract</h3>
 * <pre>
 * POST /api/test/im/broadcast
 * Content-Type: application/json
 * {
 *   "userIds":   [101, 102],           // recipients (required, non-empty)
 *   "eventType": "member_added",       // WS frame type (required)
 *   "payload":   { "conversationId": 5, ... }  // arbitrary event data (required)
 * }
 * Response 200: { "delivered": 2 }
 * Response 400: validation error
 * </pre>
 *
 * <h3>Allowed event types</h3>
 * Any value defined in {@link ImConstants} WS_* constants is permitted.  The endpoint
 * does NOT restrict to member events only so test authors can probe any WS event path.
 */
@Slf4j
@RestController
@RequestMapping("/api/test/im")
@Profile("test")
@RequiredArgsConstructor
public class TestImBroadcastController {

    /**
     * Recognised WS event types for input-validation logging.
     * Not used as a hard allowlist — caller may send any string — but unknown types
     * are logged at WARN so test authors notice typos quickly.
     */
    private static final Set<String> KNOWN_MEMBER_EVENT_TYPES = Set.of(
            ImConstants.WS_MEMBER_ADDED,
            ImConstants.WS_MEMBER_REMOVED,
            ImConstants.WS_SELF_KICKED,
            ImConstants.WS_MEMBER_LEFT,
            ImConstants.WS_CONVERSATION_RENAMED,
            ImConstants.WS_CONVERSATION_DISSOLVED,
            ImConstants.WS_CONVERSATION_DELETED,
            ImConstants.WS_CONVERSATION_UPDATED,
            ImConstants.WS_ANNOUNCEMENT_UPDATED,
            ImConstants.WS_ANNOUNCEMENT_CLEARED
    );

    private final ImWebSocketHandler webSocketHandler;

    // -------------------------------------------------------------------------
    // Request / response DTOs (static inner classes — no separate file needed)
    // -------------------------------------------------------------------------

    /** Request body accepted by POST /api/test/im/broadcast. */
    public record BroadcastRequest(
            @NotEmpty(message = "userIds must not be empty")
            List<Long> userIds,

            @NotBlank(message = "eventType must not be blank")
            String eventType,

            @jakarta.validation.constraints.NotNull(message = "payload must not be null")
            Map<String, Object> payload
    ) {}

    /** Response body returned on success. */
    public record BroadcastResponse(int delivered) {}

    // -------------------------------------------------------------------------
    // Endpoint
    // -------------------------------------------------------------------------

    /**
     * POST /api/test/im/broadcast
     * <p>
     * Broadcasts an IM WebSocket event to the given userIds via the real
     * {@link ImWebSocketHandler#broadcastEvent} path.
     */
    @PostMapping("/broadcast")
    public ResponseEntity<BroadcastResponse> broadcast(
            @Valid @RequestBody BroadcastRequest request) {

        if (!KNOWN_MEMBER_EVENT_TYPES.contains(request.eventType())) {
            log.warn("[TEST-BRIDGE] Unknown IM event type '{}' — broadcasting anyway (test may be intentional)",
                    request.eventType());
        }

        log.info("[TEST-BRIDGE] Broadcasting IM event: type={}, recipients={}, payload={}",
                request.eventType(), request.userIds(), request.payload());

        webSocketHandler.broadcastEvent(request.userIds(), request.eventType(), request.payload());

        return ResponseEntity.ok(new BroadcastResponse(request.userIds().size()));
    }
}
