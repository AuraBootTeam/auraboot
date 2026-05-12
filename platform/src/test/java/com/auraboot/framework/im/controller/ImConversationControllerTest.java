package com.auraboot.framework.im.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.websocket.ImWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ImConversationControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private ImConversationService conversationService;

    @Mock
    private ImWebSocketHandler webSocketHandler;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void addMembersAcceptsLegacyHumanArrayBody() throws Exception {
        MetaContext.setContext(7L, 11L, "user-pid", "tester");
        ImConversationController controller = new ImConversationController(conversationService, webSocketHandler);
        JsonNode body = OBJECT_MAPPER.readTree("[101,102]");

        controller.addMembers(88L, body);

        verify(conversationService).addMembers(88L, List.of(101L, 102L), 7L);
        verify(conversationService).addAgentMembers(88L, List.of(), 7L);
    }

    @Test
    void addMembersAcceptsHumanAndAgentObjectBody() throws Exception {
        MetaContext.setContext(7L, 11L, "user-pid", "tester");
        ImConversationController controller = new ImConversationController(conversationService, webSocketHandler);
        JsonNode body = OBJECT_MAPPER.readTree("{\"memberIds\":[101],\"agentIds\":[201,202]}");

        controller.addMembers(88L, body);

        verify(conversationService).addMembers(88L, List.of(101L), 7L);
        verify(conversationService).addAgentMembers(88L, List.of(201L, 202L), 7L);
    }
}
