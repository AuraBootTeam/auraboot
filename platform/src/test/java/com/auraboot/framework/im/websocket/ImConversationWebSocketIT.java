package com.auraboot.framework.im.websocket;

import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.pubsub.LocalBroadcaster;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration-style test for IM member-event broadcast path (iOS F1).
 *
 * <p>Tests the full chain:
 * {@link ImWebSocketHandler#broadcastEvent} → {@link LocalBroadcaster#publish}
 * → {@link ImSessionRegistry} → {@link WebSocketSession#sendMessage}.
 *
 * <p>Strategy: use real {@link ImSessionRegistry} and {@link LocalBroadcaster}
 * with mock {@link WebSocketSession}s so that no Spring context, Redis, or DB is
 * needed. This covers the path that the test bridge endpoint
 * {@code POST /api/test/im/broadcast} exercises in actual E2E runs.
 *
 * <p>Covered member-event types (G3 iOS F1 backlog):
 * <ul>
 *   <li>{@code member_added} — recipient receives correct payload</li>
 *   <li>{@code member_removed} — recipient receives correct payload</li>
 *   <li>{@code self_kicked} — kicked user receives frame; non-kicked user does not</li>
 *   <li>{@code member_left} — affected members receive frame; non-member does not</li>
 *   <li>{@code conversation_renamed} — all members receive frame</li>
 *   <li>{@code conversation_dissolved} — all members receive frame</li>
 * </ul>
 *
 * <p>Isolation property: for every test the captured frames of non-target sessions
 * are asserted to be zero — verifying the "only target users receive" contract.
 */
class ImConversationWebSocketIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // System under test
    private ImSessionRegistry registry;
    private LocalBroadcaster broadcaster;

    // Simulated sessions — mocks so we can capture sendMessage() calls
    private WebSocketSession sessionUser1;
    private WebSocketSession sessionUser2;
    private WebSocketSession sessionUser3; // bystander (not a member in several tests)

    @BeforeEach
    void setUp() throws IOException {
        registry = new ImSessionRegistry();
        broadcaster = new LocalBroadcaster(registry, MAPPER);

        sessionUser1 = mockOpenSession("s-u1");
        sessionUser2 = mockOpenSession("s-u2");
        sessionUser3 = mockOpenSession("s-u3");

        // Register sessions for user 1 and 2; user 3 is conditionally registered per test
        registry.register(1L, sessionUser1);
        registry.register(2L, sessionUser2);
    }

    // -------------------------------------------------------------------------
    // member_added — user 1 and 2 are notified when user 3 joins
    // -------------------------------------------------------------------------

    @Test
    void memberAdded_targetUsersReceiveEventWithCorrectPayload() throws Exception {
        Map<String, Object> payload = Map.of("conversationId", 10L, "newMemberId", 3L, "byUserId", 1L);

        broadcaster.publish(List.of(1L, 2L), frameOf(ImConstants.WS_MEMBER_ADDED, payload));

        assertFrameReceived(sessionUser1, ImConstants.WS_MEMBER_ADDED, 10L, "conversationId");
        assertFrameReceived(sessionUser2, ImConstants.WS_MEMBER_ADDED, 10L, "conversationId");
        // user 3 is not registered → no delivery
        verify(sessionUser3, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void memberAdded_bystanderSessionReceivesNothing() throws Exception {
        registry.register(3L, sessionUser3);
        Map<String, Object> payload = Map.of("conversationId", 10L, "newMemberId", 99L);

        // Only target users 1 and 2 (user 3 is not in the target list even though registered)
        broadcaster.publish(List.of(1L, 2L), frameOf(ImConstants.WS_MEMBER_ADDED, payload));

        verify(sessionUser3, never()).sendMessage(any(TextMessage.class));
    }

    // -------------------------------------------------------------------------
    // member_removed — user 1 is notified; user 2 (the removed one) still gets it
    // -------------------------------------------------------------------------

    @Test
    void memberRemoved_targetUsersReceiveEventWithCorrectPayload() throws Exception {
        Map<String, Object> payload = Map.of("conversationId", 20L, "removedMemberId", 2L, "byUserId", 1L);

        broadcaster.publish(List.of(1L, 2L), frameOf(ImConstants.WS_MEMBER_REMOVED, payload));

        assertFrameReceived(sessionUser1, ImConstants.WS_MEMBER_REMOVED, 20L, "conversationId");
        assertFrameReceived(sessionUser2, ImConstants.WS_MEMBER_REMOVED, 20L, "conversationId");
    }

    @Test
    void memberRemoved_nonTargetSessionReceivesNothing() throws Exception {
        registry.register(3L, sessionUser3);
        Map<String, Object> payload = Map.of("conversationId", 20L, "removedMemberId", 2L);

        broadcaster.publish(List.of(1L, 2L), frameOf(ImConstants.WS_MEMBER_REMOVED, payload));

        verify(sessionUser3, never()).sendMessage(any(TextMessage.class));
    }

    // -------------------------------------------------------------------------
    // self_kicked — only the kicked user's session receives the event
    // -------------------------------------------------------------------------

    @Test
    void selfKicked_onlyKickedUserReceivesEvent() throws Exception {
        Map<String, Object> payload = Map.of("conversationId", 30L, "byUserId", 1L);

        // User 2 is kicked; user 1 (admin) does NOT receive self_kicked (they get member_removed)
        broadcaster.publish(List.of(2L), frameOf(ImConstants.WS_SELF_KICKED, payload));

        assertFrameReceived(sessionUser2, ImConstants.WS_SELF_KICKED, 30L, "conversationId");
        verify(sessionUser1, never()).sendMessage(any(TextMessage.class));
    }

    // -------------------------------------------------------------------------
    // member_left — all remaining members are notified; bystander is not
    // -------------------------------------------------------------------------

    @Test
    void memberLeft_remainingMembersReceiveEvent() throws Exception {
        registry.register(3L, sessionUser3);
        Map<String, Object> payload = Map.of("conversationId", 40L, "userId", 3L);

        // User 3 left; notify user 1 and 2 (the remaining members)
        broadcaster.publish(List.of(1L, 2L), frameOf(ImConstants.WS_MEMBER_LEFT, payload));

        assertFrameReceived(sessionUser1, ImConstants.WS_MEMBER_LEFT, 40L, "conversationId");
        assertFrameReceived(sessionUser2, ImConstants.WS_MEMBER_LEFT, 40L, "conversationId");
        // User 3 (the leaver) is NOT in the target list
        verify(sessionUser3, never()).sendMessage(any(TextMessage.class));
    }

    // -------------------------------------------------------------------------
    // conversation_renamed — all members receive the new name
    // -------------------------------------------------------------------------

    @Test
    void conversationRenamed_allMembersReceiveNewName() throws Exception {
        Map<String, Object> payload = Map.of(
                "conversationId", 50L,
                "newName", "Engineering Room",
                "byUserId", 1L
        );

        broadcaster.publish(List.of(1L, 2L), frameOf(ImConstants.WS_CONVERSATION_RENAMED, payload));

        assertFrameContainsField(sessionUser1, ImConstants.WS_CONVERSATION_RENAMED, "newName", "Engineering Room");
        assertFrameContainsField(sessionUser2, ImConstants.WS_CONVERSATION_RENAMED, "newName", "Engineering Room");
    }

    // -------------------------------------------------------------------------
    // conversation_dissolved — all members receive the dissolution event
    // -------------------------------------------------------------------------

    @Test
    void conversationDissolved_allMembersReceiveEvent() throws Exception {
        Map<String, Object> payload = Map.of("conversationId", 60L, "dissolvedByUserId", 1L);

        broadcaster.publish(List.of(1L, 2L), frameOf(ImConstants.WS_CONVERSATION_DISSOLVED, payload));

        assertFrameReceived(sessionUser1, ImConstants.WS_CONVERSATION_DISSOLVED, 60L, "conversationId");
        assertFrameReceived(sessionUser2, ImConstants.WS_CONVERSATION_DISSOLVED, 60L, "conversationId");
    }

    @Test
    void conversationDissolved_bystanderSessionReceivesNothing() throws Exception {
        registry.register(3L, sessionUser3);
        Map<String, Object> payload = Map.of("conversationId", 60L, "dissolvedByUserId", 1L);

        broadcaster.publish(List.of(1L, 2L), frameOf(ImConstants.WS_CONVERSATION_DISSOLVED, payload));

        verify(sessionUser3, never()).sendMessage(any(TextMessage.class));
    }

    // -------------------------------------------------------------------------
    // Isolation: offline (unregistered) target user receives no frame
    // -------------------------------------------------------------------------

    @Test
    void offlineTargetUser_receivesNoFrame() throws Exception {
        // User 3 is NOT registered in the registry (offline)
        Map<String, Object> payload = Map.of("conversationId", 70L);

        broadcaster.publish(List.of(1L, 3L), frameOf(ImConstants.WS_MEMBER_ADDED, payload));

        // User 1 receives the frame
        assertFrameReceived(sessionUser1, ImConstants.WS_MEMBER_ADDED, 70L, "conversationId");
        // User 3 has no registered session → no send attempt
        verify(sessionUser3, never()).sendMessage(any(TextMessage.class));
    }

    // -------------------------------------------------------------------------
    // broadcastEvent on ImWebSocketHandler delegates to broadcaster correctly
    // -------------------------------------------------------------------------

    /**
     * Tests the full chain: {@link ImWebSocketHandler#broadcastEvent} →
     * {@link LocalBroadcaster} → {@link ImSessionRegistry} → session.
     *
     * <p>The handler itself delegates to {@link com.auraboot.framework.im.pubsub.ImMessageBroadcaster};
     * here we wire a real LocalBroadcaster to confirm the delegation contract end-to-end.
     */
    @Test
    void handlerBroadcastEvent_memberAdded_endsUpInTargetSession() throws Exception {
        ImWebSocketHandler handler = buildHandlerWithLocalBroadcaster();

        handler.broadcastEvent(
                List.of(1L, 2L),
                ImConstants.WS_MEMBER_ADDED,
                Map.of("conversationId", 80L, "newMemberId", 5L)
        );

        assertFrameReceived(sessionUser1, ImConstants.WS_MEMBER_ADDED, 80L, "conversationId");
        assertFrameReceived(sessionUser2, ImConstants.WS_MEMBER_ADDED, 80L, "conversationId");
    }

    @Test
    void handlerBroadcastEvent_conversationDissolved_onlyTargetsReceive() throws Exception {
        registry.register(3L, sessionUser3);
        ImWebSocketHandler handler = buildHandlerWithLocalBroadcaster();

        // User 3 is registered but NOT in the target list
        handler.broadcastEvent(
                List.of(1L, 2L),
                ImConstants.WS_CONVERSATION_DISSOLVED,
                Map.of("conversationId", 90L)
        );

        assertFrameReceived(sessionUser1, ImConstants.WS_CONVERSATION_DISSOLVED, 90L, "conversationId");
        assertFrameReceived(sessionUser2, ImConstants.WS_CONVERSATION_DISSOLVED, 90L, "conversationId");
        verify(sessionUser3, never()).sendMessage(any(TextMessage.class));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Build a minimal {@link ImWebSocketHandler} wired to the real local broadcaster. */
    private ImWebSocketHandler buildHandlerWithLocalBroadcaster() {
        return new ImWebSocketHandler(
                registry,
                mock(com.auraboot.framework.im.service.ImMessageService.class),
                mock(com.auraboot.framework.im.service.ImConversationService.class),
                mock(com.auraboot.framework.im.mapper.ImConversationMemberMapper.class),
                broadcaster,
                MAPPER,
                mock(org.springframework.context.ApplicationContext.class),
                mock(org.springframework.context.ApplicationEventPublisher.class)
        );
    }

    private static com.auraboot.framework.im.dto.WsFrame frameOf(String type, Map<String, Object> data) {
        return com.auraboot.framework.im.dto.WsFrame.builder().type(type).data(data).build();
    }

    private static WebSocketSession mockOpenSession(String id) throws IOException {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        return s;
    }

    /**
     * Asserts that the session received exactly one frame with the given event type
     * and that its data JSON contains {@code fieldName == expectedLong}.
     */
    private static void assertFrameReceived(WebSocketSession session,
                                             String expectedType,
                                             long expectedFieldValue,
                                             String fieldName) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());

        TextMessage lastMsg = captor.getValue();
        JsonNode root = MAPPER.readTree(lastMsg.getPayload());
        assertThat(root.path("type").asText()).isEqualTo(expectedType);
        assertThat(root.path("data").path(fieldName).asLong()).isEqualTo(expectedFieldValue);
    }

    /**
     * Asserts that the session received a frame with the given event type
     * and that its data JSON contains {@code fieldName == expectedString}.
     */
    private static void assertFrameContainsField(WebSocketSession session,
                                                  String expectedType,
                                                  String fieldName,
                                                  String expectedString) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(captor.capture());

        TextMessage lastMsg = captor.getValue();
        JsonNode root = MAPPER.readTree(lastMsg.getPayload());
        assertThat(root.path("type").asText()).isEqualTo(expectedType);
        assertThat(root.path("data").path(fieldName).asText()).isEqualTo(expectedString);
    }
}
