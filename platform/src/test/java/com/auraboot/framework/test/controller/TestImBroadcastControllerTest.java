package com.auraboot.framework.test.controller;

import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.websocket.ImWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit / web-slice tests for {@link TestImBroadcastController}.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>The controller is annotated with {@code @Profile("test")} — confirms test-only guard</li>
 *   <li>The controller maps to {@code /api/test/im}</li>
 *   <li>Happy-path: valid request invokes {@code broadcastEvent} with correct arguments</li>
 *   <li>All member-event types accepted by the spec are forwarded</li>
 *   <li>Invalid input (missing fields) returns HTTP 400 without calling the broadcast service</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TestImBroadcastControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ImWebSocketHandler webSocketHandler;

    private MockMvc mockMvc;
    private TestImBroadcastController controller;

    @BeforeEach
    void setUp() {
        controller = new TestImBroadcastController(webSocketHandler);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                // Wire standard Jakarta Validation so @Valid works in standalone mode
                .setValidator(new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean() {{
                    afterPropertiesSet();
                }})
                // Wire Jackson so Java record response bodies serialize as JSON objects
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    // -------------------------------------------------------------------------
    // Guard annotations
    // -------------------------------------------------------------------------

    @Test
    void controllerHasTestProfileAnnotation() {
        Profile profile = TestImBroadcastController.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("test");
    }

    @Test
    void controllerHasRestControllerAnnotation() {
        assertThat(TestImBroadcastController.class).hasAnnotation(RestController.class);
    }

    @Test
    void controllerMapsToApiTestIm() {
        RequestMapping mapping = TestImBroadcastController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/api/test/im");
    }

    // -------------------------------------------------------------------------
    // Happy-path: member_added
    // -------------------------------------------------------------------------

    @Test
    void broadcastMemberAddedInvokesBroadcastEventWithCorrectArgs() throws Exception {
        Map<String, Object> requestBody = Map.of(
                "userIds", List.of(101L, 102L),
                "eventType", ImConstants.WS_MEMBER_ADDED,
                "payload", Map.of("conversationId", 5L, "byUserId", 99L)
        );

        mockMvc.perform(post("/api/test/im/broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> recipientCaptor = forClass(List.class);
        ArgumentCaptor<String> typeCaptor = forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = forClass(Map.class);

        verify(webSocketHandler).broadcastEvent(
                recipientCaptor.capture(), typeCaptor.capture(), payloadCaptor.capture());

        assertThat(recipientCaptor.getValue()).containsExactlyInAnyOrder(101L, 102L);
        assertThat(typeCaptor.getValue()).isEqualTo(ImConstants.WS_MEMBER_ADDED);
        assertThat(payloadCaptor.getValue()).containsEntry("conversationId", 5);
    }

    // -------------------------------------------------------------------------
    // All G3 member-event types are forwarded
    // -------------------------------------------------------------------------

    @Test
    void broadcastMemberRemoved() throws Exception {
        assertEventTypeForwarded(ImConstants.WS_MEMBER_REMOVED, List.of(200L));
    }

    @Test
    void broadcastSelfKicked() throws Exception {
        assertEventTypeForwarded(ImConstants.WS_SELF_KICKED, List.of(300L));
    }

    @Test
    void broadcastMemberLeft() throws Exception {
        assertEventTypeForwarded(ImConstants.WS_MEMBER_LEFT, List.of(400L, 401L));
    }

    @Test
    void broadcastConversationRenamed() throws Exception {
        assertEventTypeForwarded(ImConstants.WS_CONVERSATION_RENAMED, List.of(500L));
    }

    @Test
    void broadcastConversationDissolved() throws Exception {
        assertEventTypeForwarded(ImConstants.WS_CONVERSATION_DISSOLVED, List.of(600L, 601L, 602L));
    }

    // -------------------------------------------------------------------------
    // Input validation: missing / blank fields return 400
    // -------------------------------------------------------------------------

    @Test
    void missingUserIdsReturns400() throws Exception {
        Map<String, Object> body = Map.of(
                "eventType", ImConstants.WS_MEMBER_ADDED,
                "payload", Map.of("conversationId", 1L)
        );

        mockMvc.perform(post("/api/test/im/broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(webSocketHandler);
    }

    @Test
    void emptyUserIdsReturns400() throws Exception {
        Map<String, Object> body = Map.of(
                "userIds", List.of(),
                "eventType", ImConstants.WS_MEMBER_ADDED,
                "payload", Map.of("conversationId", 1L)
        );

        mockMvc.perform(post("/api/test/im/broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(webSocketHandler);
    }

    @Test
    void missingEventTypeReturns400() throws Exception {
        Map<String, Object> body = Map.of(
                "userIds", List.of(101L),
                "payload", Map.of("conversationId", 1L)
        );

        mockMvc.perform(post("/api/test/im/broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(webSocketHandler);
    }

    @Test
    void blankEventTypeReturns400() throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("userIds", List.of(101L));
        body.put("eventType", "   ");
        body.put("payload", Map.of("conversationId", 1L));

        mockMvc.perform(post("/api/test/im/broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(webSocketHandler);
    }

    @Test
    void missingPayloadReturns400() throws Exception {
        Map<String, Object> body = Map.of(
                "userIds", List.of(101L),
                "eventType", ImConstants.WS_MEMBER_ADDED
        );

        mockMvc.perform(post("/api/test/im/broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(webSocketHandler);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void assertEventTypeForwarded(String eventType, List<Long> recipients) throws Exception {
        Map<String, Object> body = Map.of(
                "userIds", recipients,
                "eventType", eventType,
                "payload", Map.of("conversationId", 1L)
        );

        mockMvc.perform(post("/api/test/im/broadcast")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(recipients.size()));

        ArgumentCaptor<String> typeCaptor = forClass(String.class);
        verify(webSocketHandler).broadcastEvent(
                org.mockito.Mockito.anyList(), typeCaptor.capture(), org.mockito.Mockito.anyMap());
        assertThat(typeCaptor.getValue()).isEqualTo(eventType);

        // Reset mock for next invocation in the same test class instance
        org.mockito.Mockito.reset(webSocketHandler);
    }
}
